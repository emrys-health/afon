package health.emrys.afon.acceptance.security

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AcceptanceTestConfig, CatalogAdminGrant, CatalogRoles, FileHelper, SpecHelper}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS_FORBIDDEN
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import health.emrys.afon.polaris.comms.PolarisConfigurer.getCreateCatalogURI
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class AwsAccessForbiddenSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAwsForbidden

  override def cloudProvider: SupportedCloudsEnum = AWS_FORBIDDEN

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()
  val GrantCatalogAdmin = new CatalogAdminGrant(this, config)
  val CatalogRoles = new CatalogRoles(this, config)
  val NAMESPACE = "forbidden"
  val FULL_NAMESPACE = s"$cloudProvider.$NAMESPACE"
  val NUM_ROWS = 10

  "Polaris" should {
    "be told about the catalog" in {
      val uri = getCreateCatalogURI
      Given(s"an admin has set up the environment with:\n" + readEnvSetup())
      val json = config.catalogConfig
      When(s"we call $uri with payload:\n$json")
      client.postJsonCall(uri, json, accessToken)
      val uriGet = s"/api/catalog/v1/config?warehouse=${config.warehouseName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = client.getCall(uriGet, accessToken)
      assert(response.getCode == 200)
    }


    "be told about the catalog admin grants" in GrantCatalogAdmin()

    "be told about the catalog roles" in CatalogRoles()
  }

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/aws/create.sh")
  }

  s"A table" should {
    "have a namespace" in {
      Given(s"a bucket for which we have no access")
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      When(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      val uri = s"/api/catalog/v1/${config.warehouseName()}/namespaces/$NAMESPACE"
      Then(s"Polaris acknowledges the namespace exists when call '$uri''")
      val response = client.getCall(uri, accessToken)
      assert(response.getCode == 200)
    }
    s"be created and populated with $NUM_ROWS rows" in {
      Given(s"a namespace '$FULL_NAMESPACE'")
      val fqn = s"$FULL_NAMESPACE.$tableName"
      When(s"we write $NUM_ROWS rows of data to a table in that namespace called '$fqn'")
      intercept[org.apache.spark.SparkException] {
        spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(fqn).create()
      }
      Then("an exception is thrown because access is forbidden")
    }
  }
}
