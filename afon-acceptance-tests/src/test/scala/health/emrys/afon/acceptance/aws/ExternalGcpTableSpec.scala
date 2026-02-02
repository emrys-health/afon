package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.sparkWarehouse
import health.emrys.afon.config.AfonProperties.{GCP_BUCKET_NAME, GOOGLE_APPLICATION_CREDENTIALS}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.{GoogleCloudConfigForPolaris, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.apache.iceberg.gcp.auth.GoogleAuthManager
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util.Properties

class ExternalGcpTableSpec  extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  val FEDERATED_CATALOG_NAME  = "emrys_afon_core_2"
  val BUCKET                  = f"gs://emrys_afon_core_2"

  "An external GCP table" should {

    val properties        = new AfonPropertiesLoader().getProperties
    val config            = new GoogleCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    properties.setProperty(GCP_BUCKET_NAME.getPropertyName, BUCKET) // bit of a hack - TODO clean me up

    "be registered with Polaris" in {
      Given(s"a catalog called '$FEDERATED_CATALOG_NAME' has already been created in GCP'")
      val json: String = externalCatalogJson(properties)
      val uri: String  = PolarisConfigurer.getCreateCatalogURI
      When(f"Polaris is invoked at URI '$uri' with payload:\n$json")

      val response = client.postJsonCall(
        uri,
        json,
        accessToken)
      val responseCode = response.getCode
      Then(s"the response code ($responseCode) is HTTP 2XX indicating the external catalog was created")
      assert(responseCode / 100 == 2)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in catalogRoles()
    "can be queried via Spark" ignore { // to work, this needs this Polaris JIRA to be fixed https://github.com/apache/polaris/issues/3451
      Given(f"a namespace called $FEDERATED_CATALOG_NAME")
      val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)

      val spark: SparkSession = SparkSession
        .builder()
        .config(sparkConf)
        .getOrCreate()

      val sparkNamespace = "ph_namespace"
      val nsSQL = s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$sparkNamespace"
      When(f"we execute:\n$nsSQL\n$nsSQL")
      spark.sql(nsSQL)

      Then("we can query the table")
      val sql = String.format("SELECT * FROM %s.%s.%s", FEDERATED_CATALOG_NAME, sparkNamespace, "GcpRest_260112450944") // TODO choose sensible name
      When(f"we execute:\n$nsSQL\n$sql")
      spark.sql(sql).show() // TODO we need to make a table that can be queried
    }
  }

  // this gives:
  // Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
  private def externalCatalogJson(properties: Properties): String = {
    val config = new GoogleCloudConfigForPolaris(properties)
    val bucketProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation(BUCKET) // TODO make configurable
      .build()

    val scopes = java.util.List.of[String](
      GoogleAuthManager.DEFAULT_SCOPES
    )
    val clientId = "my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
    val clientSecret = ***REMOVED***
    val authenticationParameters =
      new OAuthClientCredentialsParameters(
        "https://oauth2.googleapis.com/token", // this throws "Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials"
        clientId,
        clientSecret,
        scopes,
        AuthenticationTypeEnum.OAUTH
      )

    val connectionInfo = new ConnectionConfigInfo(
      ConnectionTypeEnum.ICEBERG_REST,
      "https://biglake.googleapis.com/iceberg/v1/restcatalog",
      authenticationParameters,
      null)

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
