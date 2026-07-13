package health.emrys.afon.acceptance

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import health.emrys.afon.acceptance.ContainerHelper.{trinoConfigProperties, trinoLocalConfig}
import health.emrys.afon.acceptance.SparkHelper.{namespace, spark}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris.METADATA_KEY
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getCreateCatalogURI, namespaceURI, tableURI}
import health.emrys.afon.trino.SmokeTest
import org.apache.polaris.core.admin.model.Catalog
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier


abstract class AbstractAccessSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  def config: CloudConfigForPolaris

  val GrantCatalogAdmin = new CatalogAdminGrant(this, config)
  val CatalogRoles      = new CatalogRoles(this, config)
  val NUM_ROWS          = 100
  val FULL_NAMESPACE    = s"${cloudProvider}.$namespace"

  protected def createTableAndPopulate(): Unit = {
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
  }


  "Polaris" should {
    val client      = AcceptanceTestConfig.instance.getPolarisAdminClient
    val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()
    "be told about the catalog" in {
      val uri = getCreateCatalogURI
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      val json = config.catalogConfig
      When(s"we call $uri as user '${client.getClientId}' with payload:\n$json")
      client.postJsonCall(uri, json, accessToken)
      val uriGet = s"/api/catalog/v1/config?warehouse=${config.warehouseName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = client.getCall(uriGet, accessToken)
      assert(response.getCode == 200)
    }

    "associate properties with catalog" in {
      Given(s"the catalog has a metadata property '$METADATA_KEY'")
      assert(config.createCatalog().getProperties.containsKey(METADATA_KEY))
      val uriGet = s"${PolarisConfigurer.getCreateCatalogURI}/${config.warehouseName()}"
      When(s"we call GET at $uriGet and deserialize the response")
      val response = client.getCall(uriGet, accessToken)
      val entity = new String(response.getEntity.getContent.readAllBytes())
      val OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      val fromPolaris = OBJECT_MAPPER.readValue(entity, classOf[Catalog]);
      val metadata = fromPolaris.getProperties
      assert(metadata.containsKey(METADATA_KEY))
      Then(s"the metadata contains something like '${metadata.get(METADATA_KEY)}' for the key '$METADATA_KEY'")
    }

    "be told about the catalog admin grants" in GrantCatalogAdmin()

    "be told about the catalog roles" in CatalogRoles()
  }

  s"A table" should {
    val client      = AcceptanceTestConfig.instance.getPolarisClient
    val accessToken = AcceptanceTestConfig.instance.getAccessToken()
    "have a namespace" in {
      import scala.jdk.CollectionConverters._
      val conf = SparkHelper.sparkWarehouse.sparkConfigs(cloudProvider).asScala.map {
        case(k, v) =>
          val value = if (k.toLowerCase.contains("token") || k.toLowerCase.contains("oauth")) "REDACTED" else v
          String.format("%-50s%50s", k, value)
      }.mkString("\n")
      Given(s"Spark is configured with:\n\n$conf")
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      When(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      val uri = namespaceURI(config.warehouseName(), namespace)
      Then(s"Polaris acknowledges the namespace exists when call as user '${client.getClientId}' '$uri")
      val response = client.getCall(uri, accessToken)
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
      Given(s"we have registered the table in catalog '${config.warehouseName()}', namespace '$namespace'")
      val uriGet = tableURI(config.warehouseName(), namespace, tableName) + "?snapshots=all"
      When(s"we call Polaris at $uriGet")
      val response = client.getCall(uriGet, accessToken)
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

  "Trino" should {
    val properties          = new AfonPropertiesLoader().getProperties
    "vend credentials" in {
      Given(s"a catalog called ${config.warehouseName()} in Polaris")

      val tableName = tableNameFQN.toLowerCase()
      And(s"a table called $tableName with $NUM_ROWS rows in cloud storage")

      And(
        s"""a Trino instance with $cloudProvider.properties of:
           |
           |${trinoLocalConfig(properties, cloudProvider)}""".stripMargin)

      val trinoHttp = trinoConfigProperties().get("discovery.uri").toString
      val url = s"jdbc:trino://${trinoHttp.substring(trinoHttp.indexOf("//") + 2)}/"
      When(s"we connect to $url")
      val smokeTest = new SmokeTest(url)
      val sql = s"SELECT COUNT(*) FROM $tableName"
      And(s"execute SQL: ${formatSQL(sql)}")
      val result = smokeTest.readTable(sql)
      Then(s"the result is $result")
      assert(Integer.parseInt(result) == NUM_ROWS)
    }
  }
}

object AbstractAccessSpec {
  val tables = scala.collection.mutable.Set[String]()
  val REST_CODE_RESOURCE_CREATED = 201
}