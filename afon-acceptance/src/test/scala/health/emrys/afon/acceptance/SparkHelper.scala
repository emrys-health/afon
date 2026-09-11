package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.ops.config.OpsDefaults
import health.emrys.afon.ops.spark.SparkChecker
import health.emrys.afon.polaris.PolarisConstants
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog
import health.emrys.afon.spark.{CatalogWarehouse, KeyCloakSparkWarehouseConfigForTesting, SparkWarehouseConfig, SparkWarehouseConfigFactory}
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

object SparkHelper {
  val namespace = OpsDefaults.ACCEPTANCE_TEST_NAMESPACE

  // Triggers creation of the Polaris principal/roles/grants exercised by the access specs.
  val principal = PolarisHelper.principal

  val CatalogName1 = "first_catalog"
  val CatalogName2 = "second_catalog"

  // In external-auth mode Spark/Iceberg must obtain its OAuth2 token from KeyCloak, not from Polaris
  // (whose /api/catalog/v1/oauth/tokens returns HTTP 501). We authenticate with the KeyCloak client
  // credentials (USER_POLARIS_ID/SECRET); Polaris maps the token's 'azp' claim back to the 'root' principal. The
  // Polaris-minted 'principal' credentials are unknown to KeyCloak, so they cannot be used here.
  val sparkWarehouse =
    new KeyCloakSparkWarehouseConfigForTesting(
      instance.getProperties,
      Array(
        new CatalogWarehouse(
          AwsForbiddenPolarisCatalog.AWS_POLARIS_FORBIDDEN,
          "forbidden"
        ),
        new CatalogWarehouse(CatalogName1, CatalogName1),
        new CatalogWarehouse(CatalogName2, CatalogName2)
      )
    )

  val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
  val spark: SparkSession = SparkSession
    .builder()
    .getOrCreate()
}