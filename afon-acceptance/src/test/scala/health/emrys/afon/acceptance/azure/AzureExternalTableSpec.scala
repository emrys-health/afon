package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, GrantCatalogAdmin, CreateCatalogRole}
import health.emrys.afon.acceptance.tags.{Azure, External}
import health.emrys.afon.config.AfonProperty.{AZURE_BUCKET_NAME, AZURE_DATABRICKS_TOKEN}
import health.emrys.afon.polaris.catalogs.{AzurePolarisCatalog, ExternalPolarisCatalog}
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen

import java.util.Collections

/**
 * This is work in progress.
 * You'll need to generate a Databricks token for this test to run successfully and put it in your secret.properties
 *
 * Test Databricks is actually ready to receive calls with:
 * curl -X GET   https://adb-7405611337270626.6.azuredatabricks.net/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/phdbx/namespaces/default   -H "Authorization: Bearer $AZURE_DATABRICKS_TOKEN"
 */
@Azure
@External
class AzureExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val properties     = AcceptanceTestConfig.instance.getProperties
  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

  val FEDERATED_CATALOG_NAME = "phdbx"
  val DATABRICKS_URI         = "https://adb-7405611337270626.6.azuredatabricks.net"

  "An external table" should {
    properties.setValue(AZURE_BUCKET_NAME, "unity-catalog-storage") // TODO get a proper bucket we can use for federation
    val azureCatalog = new AzurePolarisCatalog(properties)

    val externalCatalog: ExternalPolarisCatalog = new ExternalPolarisCatalog(properties) {
      override def catalogName(): String = FEDERATED_CATALOG_NAME
      override protected def bucketUri(): String =
        "abfss://unity-catalog-storage@dbstorageipvnyon4atfem.dfs.core.windows.net/7405611337270626/__unitystorage/catalogs/92a89c86-f5de-4c3c-9fd0-2f0bc68b5888/tables/c8316ca8-4aa3-4b04-8248-fa560bba8463"
      override protected def storageConfigInfo(): StorageConfigInfo = azureCatalog.storageConfigInfo()
      override protected def remoteCatalogName(): String = FEDERATED_CATALOG_NAME
      override protected def scopes(): java.util.List[String] = Collections.emptyList()
      override protected def endpoint(): String = DATABRICKS_URI

      override protected def createCatalog(): ExternalCatalog = {
        val bucketProperties = CatalogProperties.builder().setDefaultBaseLocation(bucketUri()).build()
        val dbToken = new BearerAuthenticationParameters(
          properties.getValue(AZURE_DATABRICKS_TOKEN),
          AuthenticationParameters.AuthenticationTypeEnum.BEARER)
        val connectionInfo = IcebergRestConnectionConfigInfo.builder()
          .setRemoteCatalogName(FEDERATED_CATALOG_NAME)
          .setConnectionType(ConnectionTypeEnum.ICEBERG_REST)
          .setUri(s"$DATABRICKS_URI/api/2.1/unity-catalog/iceberg-rest/")
          .setAuthenticationParameters(dbToken)
          .build()
        val catalog = new ExternalCatalog(
          connectionInfo,
          Catalog.TypeEnum.EXTERNAL,
          FEDERATED_CATALOG_NAME,
          bucketProperties,
          System.currentTimeMillis(),
          System.currentTimeMillis(),
          null,
          storageConfigInfo())
        catalog
      }
    }

    val grantCatalogAdmin = new GrantCatalogAdmin(this, externalCatalog)
    val createCatalogRole = new CreateCatalogRole(this, externalCatalog)
    val tableName         = "diamonds"
    val sparkNamespace    = "ph_glue_iceberg_db"

    "be visible to our Polaris" in {
      Given(f"a Unity catalog called $FEDERATED_CATALOG_NAME already exists")
      When(f"Polaris is configured with the federated catalog '$FEDERATED_CATALOG_NAME'")
      val responses = catalogManager.configureCatalogs(externalCatalog)
      assert(responses.size() > 0)
      val responseCode  = responses.get(0).getCode
      Then(s"the response code ($responseCode) is HTTP 2XX indicating the external catalog was created or 409 indicating it already exists")
      assert(responseCode / 100 == 2 || responseCode == 409)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in createCatalogRole()
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

}
