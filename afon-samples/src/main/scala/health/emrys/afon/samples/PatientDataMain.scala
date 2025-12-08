package health.emrys.afon.samples

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum._
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.infra.{ClusterPolarisConfiguration, EmrysCloud}
import health.emrys.afon.samples.SampleData.{occupancyDataset, patientDataset}
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

import scala.util.Random

case class Patient(
                    id: Long,
                    age: Int
                  )

case class Occupancy(
                      id: Long,
                      numDays: Int
                    )


object PatientDataMain {

  val NAMESPACE       = "samples2"
  val PATIENTS_TABLE  = "patients"
  val OCCUPANCY_TABLE = "occupancy"

  def main(array: Array[String]): Unit = {
    val spark: SparkSession = initSpark
    val cluster = new ClusterPolarisConfiguration(new AfonPropertiesLoader().getProperties);
    cluster.initializePolaris()
    createData(spark, 10000)
  }

  private def createData(spark: SparkSession, n: Int): Unit = {
    val fqn_patients = f"aws.$NAMESPACE.$PATIENTS_TABLE"
    val fqn_occupancy = f"gcp.${NAMESPACE}.${OCCUPANCY_TABLE}"
    List(AWS, GCP).foreach(x => prepareNamespace(spark, x))
    spark.sql(f"DROP TABLE IF EXISTS $fqn_patients  PURGE")
    spark.sql(f"DROP TABLE IF EXISTS $fqn_occupancy PURGE")
    patientDataset(spark, n).writeTo(fqn_patients).options(Map("mode" -> "overwrite")).create()
    occupancyDataset(spark, n).writeTo(fqn_occupancy).options(Map("mode" -> "overwrite")).create()
  }

  private def prepareNamespace(spark: SparkSession, supportedCloud: SupportedCloudsEnum): Unit = {
    val cloud = supportedCloud.toString()
    //    spark.sql(f"DROP SCHEMA IF EXISTS $cloud.$NAMESPACE")
    //    spark.sql(f"DROP NAMESPACE IF EXISTS $cloud.$NAMESPACE")
    spark.sql(f"CREATE NAMESPACE IF NOT EXISTS $cloud.$NAMESPACE")
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
