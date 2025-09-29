package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.AcceptanceTestConfig.instance
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

object SparkHelper {
  val namespace = "acceptancetests"
  val sparkWarehouse = new SparkWarehouseConfig(
    instance.getAccessToken,
    instance.getClientId,
    instance.getClientSecret);
  val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
  val spark: SparkSession = SparkSession
    .builder()
    .getOrCreate()
}
