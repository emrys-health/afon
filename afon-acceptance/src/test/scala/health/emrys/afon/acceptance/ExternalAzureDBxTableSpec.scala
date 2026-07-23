package health.emrys.afon.acceptance

import SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance
import health.emrys.afon.config.AfonProperties.{AZURE_BUCKET, DATABRICKS_TOKEN}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.{AzureCloudConfigForPolaris, AzureUtils, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

/**
 * This is work in progress.
 * You'll need to generate a Databricks token for this test to run successfully and put it in your env.properties
 *
 * Test Databricks is actually ready to receive calls with:
 * curl -X GET   https://adb-7405611337270626.6.azuredatabricks.net/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/phdbx/namespaces/default   -H "Authorization: Bearer $DATABRICKS_TOKEN"
 */
class ExternalAzureDBxTableSpec extends SpecPretifier with GivenWhenThen {

  val client      = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()

  val FEDERATED_CATALOG_NAME = "phdbx"

  "An external table" should {
    val properties        = new AfonPropertiesLoader().getProperties
    properties.setProperty(AZURE_BUCKET.getPropertyName, "unity-catalog-storage") // TODO get a proper bucket we can use for federation
    val config            = new AzureCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new acceptance.CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    val tableName         = "diamonds"
    val sparkNamespace    = "ph_glue_iceberg_db"

    "be visible to our Polaris" in {
      Given(f"a Unity catalog called $FEDERATED_CATALOG_NAME already exists")
      val databricksToken = DATABRICKS_TOKEN.getValueFrom(properties)
      val json: String = externalCatalogJson(config, databricksToken)
      val uri: String  = PolarisConfigurer.getCreateCatalogURI
      When(f"Polaris is invoked at URI '$uri' with payload:\n$json")

      val response = client.postJsonCall(
        uri,
        json,
        accessToken)
      val responseCode = response.getCode
      Then(s"the response code ($responseCode) is HTTP 2XX indicating the external catalog was created or 409 indicating it already exists")
      assert(responseCode / 100 == 2 || responseCode == 409)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in catalogRoles()
    "can be queried via Spark" ignore { // I deleted the DBx environment in our Azure. This used to work but we need to restore it
      Given(f"a namespace called $FEDERATED_CATALOG_NAME")
      val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
      val spark: SparkSession = SparkSession
        .builder()
        .config(sparkConf)
        .getOrCreate()

      val nsSQL = s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$sparkNamespace"
      val sql = String.format("SELECT * FROM %s.%s.%s", FEDERATED_CATALOG_NAME, sparkNamespace, tableName)
      When(f"we execute:\n$nsSQL\n$sql")
      spark.sql(nsSQL)

      Then("we can query the table")
//      spark.sql(sql).show() // TODO we need to make a table that can be queried
    }
  }


  private def externalCatalogJson(config: AzureCloudConfigForPolaris, databricksToken: String): String = {
    val bucketProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation("abfss://unity-catalog-storage@dbstorageipvnyon4atfem.dfs.core.windows.net/7405611337270626/__unitystorage/catalogs/92a89c86-f5de-4c3c-9fd0-2f0bc68b5888/tables/c8316ca8-4aa3-4b04-8248-fa560bba8463")
      .build()

    val dbToken =
      new BearerAuthenticationParameters(
        databricksToken,
        AuthenticationParameters.AuthenticationTypeEnum.BEARER)

    val connectionInfo =  IcebergRestConnectionConfigInfo.builder()
      .setRemoteCatalogName("phdbx")
      .setConnectionType(ConnectionTypeEnum.ICEBERG_REST)
      .setUri(f"https://adb-7405611337270626.6.azuredatabricks.net/api/2.1/unity-catalog/iceberg-rest/")
      .setAuthenticationParameters(dbToken)
      .build()

    val catalog =
      new ExternalCatalog(
        connectionInfo,
        Catalog.TypeEnum.EXTERNAL,
        FEDERATED_CATALOG_NAME,
        bucketProperties,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        null,
        config.getStorageConfigInfo())

    new JsonMaker().toJson(new CreateCatalogRequest(catalog))
  }
}