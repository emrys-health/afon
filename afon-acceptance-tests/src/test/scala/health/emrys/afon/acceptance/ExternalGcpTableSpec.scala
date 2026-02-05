package health.emrys.afon.acceptance

import SparkHelper.sparkWarehouse
import health.emrys.afon.config.AfonProperties.{GCP_BUCKET_NAME, GOOGLE_APPLICATION_CREDENTIALS, GCP_SERVICE_ACCOUNT}
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
  val BUCKET                  = f"emrys_afon_core_2"

  "An external GCP table" should {

    val properties        = new AfonPropertiesLoader().getProperties
//    properties.setProperty(GCP_BUCKET_NAME.getPropertyName,"emrys_afon_core_2")
//    properties.remove(GOOGLE_APPLICATION_CREDENTIALS.getPropertyName)
//    properties.setProperty(GCP_SERVICE_ACCOUNT.getPropertyName,"764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com")
//    print("properties = " + properties)
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


    val sparkNamespace = "ph_namespace"
    val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
    //      sparkConf.set(f"spark.sql.catalog.$FEDERATED_CATALOG_NAME.header.x-goog-user-project", "fjords-450014")
    //      sparkConf.set(f"spark.sql.catalog.$FEDERATED_CATALOG_NAME.warehouse", "gs://emrys_afon_core_2")
    //      System.setProperty("GOOGLE_APPLICATION_CREDENTIALS",
    //        """{ "type": "service_account", "project_id": "fjords-450014", "private_key_id": "***REMOVED***", "private_key": "-----BEGIN PRIVATE KEY-----REMOVED-----END PRIVATE KEY-----\n", "client_email": "my-vendable-sa@fjords-450014.iam.gserviceaccount.com", "client_id": "103905077815523834346", "auth_uri": "https://accounts.google.com/o/oauth2/auth", "token_uri": "https://oauth2.googleapis.com/token", "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs", "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/my-vendable-sa%40fjords-450014.iam.gserviceaccount.com", "universe_domain": "googleapis.com", "quota_project_id": "fjords-450014" }""");
    //      println(s"Using GOOGLE_APPLICATION_CREDENTIALS = ${System.getenv("GOOGLE_APPLICATION_CREDENTIALS")}")
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
    val bucketProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation(f"gs://$BUCKET") // TODO make configurable
      .build()

    // causes HTTP 400 invalid_grant on the Polaris/GCP side for /home/henryp/.config/gcloud_works2/application_default_credentials.json
//    val clientId = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com" //my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
//    val clientSecret = "***REMOVED***" //GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties)    val clientId = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com" //my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
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
        AuthenticationTypeEnum.OAUTH // OAuth leads to: Malformed request: Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials
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
