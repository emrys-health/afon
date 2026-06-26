package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.config.AfonProperties.KEY_CLOAK_HOSTNAME
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.PolarisConstants
import health.emrys.afon.polaris.keycloak.KeyCloakConstants
import health.emrys.afon.spark.KeyCloakSparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

object SparkHelper {
  val namespace = PolarisConstants.ACCEPTANCE_TEST_NAMESPACE

  // Triggers creation of the Polaris principal/roles/grants exercised by the access specs.
  val principal = PolarisHelper.principal

  // In external-auth mode Spark/Iceberg must obtain its OAuth2 token from KeyCloak, not from Polaris
  // (whose /api/catalog/v1/oauth/tokens returns HTTP 501). We authenticate with the KeyCloak client
  // credentials (root/s3cr3t); Polaris maps the token's 'azp' claim back to the 'root' principal. The
  // Polaris-minted 'principal' credentials are unknown to KeyCloak, so they cannot be used here.
  private val keyCloakTokenUri =
    KeyCloakConstants.tokenEndpoint(KEY_CLOAK_HOSTNAME.getValueFrom(new AfonPropertiesLoader().getProperties))

  val sparkWarehouse = new KeyCloakSparkWarehouseConfig(
    instance.getClientId,
    instance.getClientSecret,
    "localhost:8181",
    "http",
    keyCloakTokenUri)
  val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
  val spark: SparkSession = SparkSession
    .builder()
    .getOrCreate()
}