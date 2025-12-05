package health.emrys.afon.samples

import health.emrys.afon.infra.EmrysCloud
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

case class Patient(
                  id: Long,
                  age: Int
                  )


object PatientDataMain {

  def main(array: Array[String]): Unit = {
    val emrysCloud = new EmrysCloud()
    val sparkWarehouse = new SparkWarehouseConfig(
      emrysCloud.getAccessToken,
      emrysCloud.getPolarisId,
      emrysCloud.getPolarisSecret,
      emrysCloud.getPolarisHost,
      "https"
    );
    val sc: SparkContext = SparkContext.getOrCreate(sparkWarehouse.sparkConf())
    val spark: SparkSession = SparkSession
      .builder()
      .getOrCreate()
    val df = spark.createDataFrame(Range(1, 100).map(i => Patient(i, i)))
    df.show()
    df.writeTo("aws.acceptancetests.patients").options(Map("mode" -> "overwrite")).create()
  }

}
