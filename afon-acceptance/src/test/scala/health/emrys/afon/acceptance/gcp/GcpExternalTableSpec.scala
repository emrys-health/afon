package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.{AcceptanceTestConfig, CatalogAdminGrant}
import health.emrys.afon.acceptance.tags.{External, Gcp}
import health.emrys.afon.config.AfonProperty.{GCP_BUCKET_NAME, GOOGLE_APPLICATION_CREDENTIALS}
import health.emrys.afon.config.FilePropertyProvider
import health.emrys.afon.polaris.catalogs.{GoogleCloudConfigForPolaris, JsonSerialiser}
import health.emrys.afon.polaris.config.PolarisConfigurer
import org.apache.iceberg.gcp.auth.GoogleAuthManager
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util.Properties

@Gcp
@External
class GcpExternalTableSpec  extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()

  val FEDERATED_CATALOG_NAME  = "emrys_afon_core_2"
  val BUCKET                  = f"emrys_afon_core_2"

  "An external GCP table" should {

    val properties        = new FilePropertyProvider().getProperties
    val config            = new GoogleCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new health.emrys.afon.acceptance.CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    properties.setProperty(GCP_BUCKET_NAME.getName, BUCKET) // bit of a hack - TODO clean me up

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


    val sparkNamespace = "ph_namespace"
    val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
    val spark: SparkSession = SparkSession
      .builder()
      .config(sparkConf)
      .getOrCreate()
    "can have a namespace created" ignore { // to work, this needs this Polaris issue to be fixed https://github.com/apache/polaris/issues/3451
      Given(f"a namespace called $FEDERATED_CATALOG_NAME")
      val nsSQL = s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$sparkNamespace"
      When(f"we createa a namespace called $sparkNamespace execute:\n$nsSQL")
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

  // this gives:
  // Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
  private def externalCatalogJson(properties: Properties): String = {
    val config = new GoogleCloudConfigForPolaris(properties)
    val warehouse = f"gs://$BUCKET"
    val bucketProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation(warehouse) // TODO make configurable
      .build()

    val scopes = java.util.List.of[String](
      GoogleAuthManager.DEFAULT_SCOPES
    )
    val clientId = "my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
    val clientSecret = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties)
    val authenticationParameters =
      new OAuthClientCredentialsParameters(
        "https://oauth2.googleapis.com/token", // this throws "Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials"
        clientId,
        clientSecret,
        scopes,
        AuthenticationTypeEnum.OAUTH // OAuth leads to: Malformed request: Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
      )

    val connectionInfo = IcebergRestConnectionConfigInfo.builder()
      .setRemoteCatalogName(warehouse)
      .setConnectionType(ConnectionTypeEnum.ICEBERG_REST)
      .setUri("https://biglake.googleapis.com/iceberg/v1/restcatalog")
      .setAuthenticationParameters(authenticationParameters)
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

    new JsonSerialiser().toJson(new CreateCatalogRequest(catalog))
  }
}
