package health.emrys.afon.acceptance

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import health.emrys.afon.acceptance.ContainerHelper.{trinoConfigProperties, trinoLocalConfig}
import health.emrys.afon.acceptance.SparkHelper.{namespace, spark}
import health.emrys.afon.config.AfonProperty.{KC_HOSTNAME, POLARIS_ENDPOINT, POLARIS_ID, POLARIS_SECRET}
import health.emrys.afon.config.{AfonPropertyProvider, AfonUser, FilePropertyProvider}
import health.emrys.afon.polaris.catalogs.PolarisCatalog
import PolarisCatalog.METADATA_KEY
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.users.TestUsers
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.ops.smoke.TrinoQuerySmoke
import health.emrys.afon.polaris.config.PolarisCatalogManager
import health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM
import health.emrys.afon.security.keycloak.KeyCloakProvider
import org.apache.polaris.core.admin.model.Catalog
import org.scalatest.GivenWhenThen

abstract class AbstractAccessSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  def catalog: PolarisCatalog

  val properties = AcceptanceTestConfig.instance.getProperties
  val grantCatalogAdmin = new GrantCatalogAdmin(this, catalog)
  val createCatalogRole = new CreateCatalogRole(this, catalog)
  val NUM_ROWS = 100
  val FULL_NAMESPACE = s"${cloudProvider}.$namespace"

  protected def createTableAndPopulate(): Unit = {
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
  }

  "Polaris" should {
    val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

    "be told about the catalog" in {
      Given(s"a Polaris endpoint")
      val json = catalog.catalogConfig
      When(s"we configure the catalog with payload:\n$json")
      catalogManager.createCatalogIfAbsent(catalog)
      val uriGet = s"/api/catalog/v1/config?warehouse=${catalog.catalogName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = catalogManager.getCatalogConfig(catalog.catalogName())
      assert(response.getCode == 200)
    }

    "associate properties with catalog" in {
      Given(s"the catalog has a metadata property '$METADATA_KEY'")
      assert(catalog.catalogContainsKey(METADATA_KEY))
      When(s"we fetch the catalog from Polaris and deserialize the response")
      val response = catalogManager.getCatalog(catalog.catalogName())
      val entity = new String(response.getEntity.getContent.readAllBytes())
      val OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      val fromPolaris = OBJECT_MAPPER.readValue(entity, classOf[Catalog]);
      val metadata = fromPolaris.getProperties
      assert(metadata.containsKey(METADATA_KEY))
      Then(s"the metadata contains something like '${metadata.get(METADATA_KEY)}' for the key '$METADATA_KEY'")
    }

    "be told about the catalog admin grants" in grantCatalogAdmin()

    "be told about the catalog roles" in createCatalogRole()
  }

  s"A table" should {
    val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

    "have a namespace" in {
      import scala.jdk.CollectionConverters._
      var conf = SparkHelper.sparkWarehouse.sparkConfigs(cloudProvider).asScala.map {
        case (k, v) =>
          val value = if (k.toLowerCase.contains("token") || k.toLowerCase.contains("oauth")) String.format("<%s>", k.toLowerCase) else v
          String.format("%-50s%50s", k, value)
      }.mkString("\n")
      conf = AcceptanceTestConfig.instance.redactSecrets(conf, "sparkConfig")
      Given(s"Spark is configured with:\n\n$conf")
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      When(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      Then(s"Polaris acknowledges the namespace exists")
      val response = catalogManager.getNamespace(catalog.catalogName(), namespace)
      assert(response.getCode == 200)
    }
    s"be created and populated with $NUM_ROWS rows" in {
      Given(s"a namespace '$FULL_NAMESPACE''")
      And(s"we write $NUM_ROWS rows of data to a table in that namespace called '$tableName'")
      createTableAndPopulate()
      Then(s"when we read the table, we find $NUM_ROWS rows of data")
      val output = spark.read.table(tableNameFQN)
      assert(output.collect().size == NUM_ROWS)
    }
    "be registered with Polaris" in {
      Given(s"we have registered the table in catalog '${catalog.catalogName()}', namespace '$namespace'")
      When(s"we call Polaris to fetch the table")
      val response = catalogManager.getTable(catalog.catalogName(), namespace, tableName)
      Then("Polaris acknowledges the table has been created")
      assert(response.getCode == 200)
      AbstractAccessSpec.tables.add(s"$FULL_NAMESPACE.$tableName")
    }
  }

  "Cross cloud tables" should {
    "require 2 clouds" in {
      if (AbstractAccessSpec.tables.size == 2) {
        val x = AbstractAccessSpec.tables.head
        val y = AbstractAccessSpec.tables.tail.head
        Given(
          s"""two tables in Polaris and cloud storage called
             | '$x'
             |and
             | '$y'
             |each with $NUM_ROWS that typically look like:
             |${spark.sql(s"SELECT * FROM $x").collect().take(3).mkString("\n")}
             |...""".stripMargin)
        val sql = s"SELECT * FROM $x AS x INNER JOIN $y AS y ON x.id = y.id"
        When(s"we execute the SQL:\n$sql")
        val xs = spark.sql(sql).collect()
        Then(s"we get $NUM_ROWS rows")
        assert(xs.size == NUM_ROWS)
        And(s"the results look like:\n${xs.take(3).mkString("\n")}\n...")
      }
    }
  }

  "Polaris and KeyCloak" should {
    val properties = AcceptanceTestConfig.instance.getProperties
    import TestUsers._
    val User = new AfonUser(UserName, UserPassword, "email@test.com", "slug", "first", "second")

    "agree on the token" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(properties) != null)
      assert(ContainerHelper.instance.startPolaris(properties) != null)

      When(s"we onboard a user '$User' for catalog ${catalog.catalogName()}' in Polaris")
      val configureUsers = new ConfigureUsersMain(TestUsers(), properties)
      configureUsers.configurePolarisUsers(catalog.catalogName())
      And("configure OAuth users for Jupyter domain")
      configureUsers.configureOAuthUsers("NotUsed.com")

      Then(s"we can get a token from KeyCloak for user '$UserName' in Realm '$KEYCLOAK_REALM'")
      val keyCloakProvider = new KeyCloakProvider("localhost", UserName, UserPassword)
      assert(keyCloakProvider.getToken(KEYCLOAK_REALM) != null)

      val userProperties: AfonPropertyProvider = new FilePropertyProvider()
      userProperties.setValue(KC_HOSTNAME, ContainerHelper.KEYCLOAK_HOSTNAME)
      userProperties.setValue(POLARIS_ENDPOINT, properties.getValue(POLARIS_ENDPOINT))
      userProperties.setValue(POLARIS_ID, UserName)
      userProperties.setValue(POLARIS_SECRET, UserPassword)
      val userCatalogManager = new PolarisCatalogManager(userProperties)
      And(s"user '$UserName' can see the table when they call Polaris")
      val response = userCatalogManager.getTable(catalog.catalogName(), namespace, tableName)
      assert(response.getCode / 100 == 2)
    }
  }

}

object AbstractAccessSpec {
  val tables = scala.collection.mutable.Set[String]()
  val REST_CODE_RESOURCE_CREATED = 201
}
