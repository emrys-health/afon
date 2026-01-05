package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.{namespace, spark}
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getCatalogAdminGrantsURI, getCatalogRolesURI, getCreateCatalogURI}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier


abstract class AbstractAccessSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val NUM_ROWS = 100
  val REST_CODE_RESOURCE_CREATED = 201
  val FULL_NAMESPACE = s"${cloudProvider}.$namespace"

  def config: CloudConfigForPolaris
  val GrantCatalogAdmin = new CatalogAdminGrant(this, config)

  protected def createTableAndPopulate(): Unit = {
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
  }

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  "Polaris" should {
    "be told about the catalog" in {
      val uri = getCreateCatalogURI
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      val json = config.catalogConfig
      When(s"we call $uri with payload:\n$json")
      client.postJsonCall(uri, json, accessToken)
      val uriGet = s"/api/catalog/v1/config?warehouse=${config.warehouseName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = client.getCall(uriGet, accessToken)
      assert(response.getCode == 200)
    }


    "be told about the catalog admin grants" in GrantCatalogAdmin()

    "be told about the catalog roles" in {
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      val uri  = getCatalogRolesURI(config.warehouseName())
      val json = config.catalogRoles
      When(s"we call '$uri' with payload:\n$json")
      val response = client.putJsonCall(uri, json, accessToken)
      Then(s"Polaris indicates the HTTP request lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
      assert(response.getCode == REST_CODE_RESOURCE_CREATED)
    }
  }

  s"A table" should {
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
      val uri = s"/api/catalog/v1/${config.warehouseName()}/namespaces/$namespace"
      Then(s"Polaris acknowledges the namespace exists when call '$uri''")
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
      val uriGet = s"/api/catalog/v1/${config.warehouseName()}/namespaces/$namespace/tables/${tableName}?snapshots=all"
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
}

object AbstractAccessSpec {
  val tables = scala.collection.mutable.Set[String]()
}