package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, GrantCatalogAdmin, CreateCatalogRole}
import health.emrys.afon.acceptance.tags.{External, Gcp}
import health.emrys.afon.config.AfonProperty.GOOGLE_APPLICATION_CREDENTIALS
import health.emrys.afon.config.{AfonCatalog, AfonCloud}
import health.emrys.afon.polaris.catalogs.{ExternalPolarisCatalog, GcpPolarisCatalog}
import org.apache.iceberg.gcp.auth.GoogleAuthManager
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen

@Gcp
@External
class GcpExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val properties     = AcceptanceTestConfig.instance.getProperties
  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

  val FEDERATED_CATALOG_NAME = "emrys_afon_core_2"
  val BUCKET                 = "emrys_afon_core_2"

  "An external GCP table" should {
    val gcpCatalogDef = AfonCatalog.adHoc("gcp_unity_test", AfonCloud.GCP, "internal", BUCKET, true)
    val gcpCatalog = new GcpPolarisCatalog(gcpCatalogDef, properties)

    // this gives:
    // Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
    val externalCatalog: ExternalPolarisCatalog = new ExternalPolarisCatalog(properties) {
      override def catalogName(): String = FEDERATED_CATALOG_NAME
      override protected def bucketUri(): String = s"gs://$BUCKET"
      override protected def storageConfigInfo(): StorageConfigInfo = gcpCatalog.storageConfigInfo()
      override protected def remoteCatalogName(): String = bucketUri()
      override protected def scopes(): java.util.List[String] = java.util.List.of[String](GoogleAuthManager.DEFAULT_SCOPES)
      override protected def endpoint(): String = "https://biglake.googleapis.com"

      override protected def createCatalog(): ExternalCatalog = {
        val bucketProperties = CatalogProperties.builder().setDefaultBaseLocation(bucketUri()).build()
        val clientId     = "my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
        val clientSecret = ***REMOVED***
        val authenticationParameters =
          new OAuthClientCredentialsParameters(
            "https://oauth2.googleapis.com/token", // this throws "Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials"
            clientId,
            clientSecret,
            scopes(),
            AuthenticationTypeEnum.OAUTH // OAuth leads to: Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
          )
        val connectionInfo = IcebergRestConnectionConfigInfo.builder()
          .setRemoteCatalogName(bucketUri())
          .setConnectionType(ConnectionTypeEnum.ICEBERG_REST)
          .setUri("https://biglake.googleapis.com/iceberg/v1/restcatalog")
          .setAuthenticationParameters(authenticationParameters)
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

    "be registered with Polaris" in {
      Given(s"a catalog called '$FEDERATED_CATALOG_NAME' has already been created in GCP'")
      When(f"Polaris is configured with the federated catalog '$FEDERATED_CATALOG_NAME'")
      val responses    = catalogManager.configureCatalogs(externalCatalog)
      val responseCode = responses.get(0).getCode
      Then(s"the response code ($responseCode) is HTTP 2XX indicating the external catalog was created")
      assert(responseCode / 100 == 2)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in createCatalogRole()


    val sparkNamespace = "ph_namespace"
    val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
    val spark: SparkSession = SparkSession
      .builder()
      .config(sparkConf)
      .getOrCreate()
    "can have a namespace created" ignore { // to work, this needs this Polaris issue to be fixed https://github.com/apache/polaris/issues/3451
      Given(f"a namespace called $FEDERATED_CATALOG_NAME")
      val nsSQL = s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$sparkNamespace"
      When(f"we create a namespace called $sparkNamespace execute:\n$nsSQL")
      spark.sql(nsSQL)
      Then(f"the namespace $sparkNamespace exists")
    }

    "can be queried via Spark" ignore {  // to work, this needs this Polaris issue to be fixed https://github.com/apache/polaris/issues/3451
      val fqn = f"$FEDERATED_CATALOG_NAME.$sparkNamespace.GcpRest_260112450944" // TODO choose sensible name
      Then("we can query the table")
      val sql = f"SELECT * FROM $fqn"
      When(f"we execute:\n$sql")
      spark.sql(sql).show() // TODO we need to make a table that can be queried
      Then("we can see the rows")
    }
  }

}
