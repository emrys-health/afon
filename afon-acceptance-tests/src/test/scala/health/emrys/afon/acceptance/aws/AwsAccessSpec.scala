package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.{namespace, spark, sparkWarehouse}
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getCatalogAdminGrantsURI, getCatalogRolesURI, getCreateCatalogURI}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier


class AwsAccessSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val NUM_ROWS = 100

  val FULL_NAMESPACE = s"${sparkWarehouse.getSparkCatalog}.$namespace"

  val config = AcceptanceTestConfig.instance.getAwsCloudConfigForPolaris
  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  "Polaris" should {
    "be told about the catalog" in {
      val uri = getCreateCatalogURI
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      When(s"we call $uri")
      client.postJsonCall(uri, config.catalogConfig, accessToken)
      val uriGet = s"/api/catalog/v1/config?warehouse=${config.warehouseName()}"
      val response = client.getCall(uriGet, accessToken)
      assert(response.getCode == 200)
    }
    "be told about the catalog admin grants" in {
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      val uri  = getCatalogAdminGrantsURI(config.warehouseName())
      val json = config.catalogAdminGrants
      When(s"we call $uri with payload:\n$json")
      client.putJsonCall(uri, json, accessToken)
    }
    "be told about the catalog roles" in {
      Given(s"a Polaris endpoint at ${client.getEndpoint}")
      val uri  = getCatalogRolesURI(config.warehouseName())
      val json = config.catalogRoles
      When(s"we call $uri with payload:\n$json")
      client.putJsonCall(uri, json, accessToken)
    }
  }

  s"A table" should {
    s"be created and populated with $NUM_ROWS rows" in {
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      Given(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      And(s"we write $NUM_ROWS rows of data to a table in that namespace called '$tableName'")
      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
      Then(s"when we read the table, we find $NUM_ROWS rows of data")
      val output = spark.read.table(tableNameFQN)
      assert(output.collect().size == NUM_ROWS)
    }
    "be registered with Polaris" in {
      Given(s"we have registered the table in catalog '${config.warehouseName()}', namespace '$namespace}'")
      val uriGet = s"/api/catalog/v1/${config.warehouseName()}/namespaces/$namespace/tables/${tableName}?snapshots=all"
      When(s"we call Polaris at $uriGet")
      val response = client.getCall(uriGet, accessToken)
      Then("Polaris acknowledges the table has been created")
      assert(response.getCode == 200)
    }
  }
}
