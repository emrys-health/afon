package health.emrys.afon.samples

import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.infra.{ClusterPolarisConfiguration, EmrysCloud}
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

case class Patient(
                  id: Long,
                  age: Int
                  )


object PatientDataMain {

  def main(array: Array[String]): Unit = {
    val spark: SparkSession = initSpark
    val cluster = new ClusterPolarisConfiguration(new AfonPropertiesLoader().getProperties);
    cluster.initializePolaris()
    createData(spark)
  }

  private def createData(spark: SparkSession): Unit = {
    val df = spark.createDataFrame(Range(1, 100).map(i => Patient(i, i)))
    df.show()
    spark.sql("CREATE NAMESPACE IF NOT EXISTS aws.acceptancetests")
    df.writeTo("aws.acceptancetests.patients").options(Map("mode" -> "overwrite")).create()
  }

  private def initSpark(): SparkSession = {
    val emrysCloud = new EmrysCloud()
    val sparkWarehouse = new SparkWarehouseConfig(
      emrysCloud.getAccessToken,
      emrysCloud.getPolarisId,
      emrysCloud.getPolarisSecret,
      emrysCloud.getPolarisHost,
      "https"
    );
    val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
    SparkSession
      .builder()
      .getOrCreate()
  }
}
