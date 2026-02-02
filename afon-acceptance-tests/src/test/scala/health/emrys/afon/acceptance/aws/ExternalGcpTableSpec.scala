package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.sparkWarehouse
import health.emrys.afon.config.AfonProperties.{GCP_BUCKET_NAME, GOOGLE_APPLICATION_CREDENTIALS}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.{AwsCloudConfigForPolaris, GoogleCloudConfigForPolaris, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.apache.iceberg.gcp.auth.GoogleAuthManager
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model.{BearerAuthenticationParameters, Catalog, CatalogProperties, ConnectionConfigInfo, CreateCatalogRequest, ExternalCatalog, ImplicitAuthenticationParameters, OAuthClientCredentialsParameters, SigV4AuthenticationParameters}
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util.Properties

class ExternalGcpTableSpec  extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  val FEDERATED_CATALOG_NAME = "emrys_afon_core_2"
  val bucket = f"gs://emrys_afon_core_2"

  "An external GCP table" should {

    val properties        = new AfonPropertiesLoader().getProperties
    val config            = new GoogleCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    properties.setProperty(GCP_BUCKET_NAME.getPropertyName, bucket) // bit of a hack - clean me up

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
      .setDefaultBaseLocation(bucket) // TODO make configurable
      .build()

    val scopes = java.util.List.of[String](
//      bucket
      GoogleAuthManager.DEFAULT_SCOPES
    )
    val clientId = "my-vendable-sa@fjords-450014.iam.gserviceaccount.com" // from GCP props
    val clientSecret = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties)
    val authenticationParameters =
      new OAuthClientCredentialsParameters(
        "https://oauth2.googleapis.com/token", // this throws "Malformed request: unsupported_grant_type: Invalid grant_type: client_credentials"
//        "https://accounts.google.com/o/oauth2/auth", // this returns HTML not a JWT token
        clientId,
        clientSecret,
        scopes,
        AuthenticationTypeEnum.OAUTH
      )

    // this works but will expire
//    val authenticationParameters = new BearerAuthenticationParameters(
//      "ya29.c.c0AZ1aNiV4ccIEkUhdz60c9YXuAOepgq41fmrCrJIDt74VyiJrBSawckXSYPbSPM8JjiZShGDziBhiU-L173-6wDb84Df3EcQgFwQfyqg18fmUSpJYtv2kl6zBtdNohqR6PixwEI06QdIdPdHphEZIbzCSIdfi74WjM7XIMVQa9uNAnBSzyXnLrAumFiwlMyhfXs5xfLnwYNc8AxtgDzKqbicevV0wY6GN8SbUQK7OR3ibtOfplNm4ElJUDUtkYPP3uaOrCzh-AYETntOJNKILNTG8jfq2WkMxOH_otQYUZBfznseaLwYTaf8M5NLGMvurPV4PdYyNdNQj3rPwj0EJxDSTiBsoqEkP2nSakSL2TU7QAff4WDYSAp2ZRAL387PpZM91eSqx-10rdkURYszyMziOfqhlQZ-hOpZJd1V3shVBfW93J_V_fZ4JvQx2BpgiI_lev2k8ea2WhvbUMtX0vgByyB69XpvMaBY0IIIeM_e-Vi_jYi46cy1-yiIXyFqZk_oY4k1slrIqz40cMur6J0-ts8vQMny8IcMQzzB8d2btnV4Sjm5oBv3nkfB3blvg2vo5tc2z3bybrdZzBBzyFJMfXSJ72wl4rdyJnMfxXQ8JZvMralg2i6i96WM51xMylvXv56R7ih1i_qS-4v2s5IOuMV7BZrR7_bOOmF2qO3oOMxpScF_vY-UcBfZmdiM66kXg8vRQ1ra2vpX944eOn46bR4zqcY_ORUfckBRyujmYy6vinbljndZq14eWapg-RWus5dm7wSW24ZQzv8IYMd6UJ0ifzb4S8gZpri_n4ZFbOWiVWqktes0do02Osi34--VWUS74MwwRt5zY2Sa1Yk3sUeOb6fX3W6sdeyuZgXa-lWQXBb1Sqlbq_4gwoMSYW7htux3MmuSV9_WYWMYbJJcOSs-nnlUp0W97m8OvRdg92X0ggswJa4ltjZsfsastW8M6MXcWxyj3nSXeaMS9u55XgOcMOnxkfmyV",
//      AuthenticationTypeEnum.BEARER)

    //  INTERNAL_SERVER_ERROR: java.lang.IllegalStateException: Unsupported authentication type: IMPLICIT
//    val authenticationParameters = new ImplicitAuthenticationParameters(AuthenticationTypeEnum.IMPLICIT)
    val connectionInfo = new ConnectionConfigInfo(
      ConnectionTypeEnum.ICEBERG_REST,
      "https://biglake.googleapis.com/iceberg/v1/restcatalog",
      authenticationParameters,
//      null,
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
