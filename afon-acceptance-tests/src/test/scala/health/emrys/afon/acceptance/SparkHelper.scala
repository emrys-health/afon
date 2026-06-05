package health.emrys.afon.acceptance

import health.emrys.afon.polaris.PolarisConstants
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

object SparkHelper {
  val namespace = PolarisConstants.ACCEPTANCE_TEST_NAMESPACE

  val principal = PolarisHelper.principle

  val sparkWarehouse = new SparkWarehouseConfig(
    principal.getCredentials.getClientId,
    principal.getCredentials.getClientSecret,
    "localhost:8181");
  val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
  val spark: SparkSession = SparkSession
    .builder()
    .getOrCreate()
}
