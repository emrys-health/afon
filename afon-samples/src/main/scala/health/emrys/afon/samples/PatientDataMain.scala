package health.emrys.afon.samples

import health.emrys.afon.config.AfonCloud._
import health.emrys.afon.config.{AfonCloud, AfonPropertyProvider, FilePropertyProvider}
import health.emrys.afon.samples.SampleData.{occupancyDataset, patientDataset}
import health.emrys.afon.polaris.config.PolarisCatalogManager
import health.emrys.afon.spark.SparkClient
import org.apache.spark.sql.SparkSession

import java.lang.String.format

case class Patient(
                    id: Long,
                    age: Int
                  )

case class Occupancy(
                      id: Long,
                      numDays: Int
                    )

class PatientDataMain

object PatientDataMain {

  val NAMESPACE       = "samples2"
  val PATIENTS_TABLE  = "patients"
  val OCCUPANCY_TABLE = "occupancy"
  val logger = org.slf4j.LoggerFactory.getLogger(classOf[PatientDataMain])

  def main(array: Array[String]): Unit = {
    val properties = new FilePropertyProvider()
    val spark: SparkSession = initSpark(properties)
    logger.info("Configuring all Polaris catalogs")
    val catalogManager = new PolarisCatalogManager(properties)
    catalogManager.configureAllCatalogs()
    logger.info("Creating data")
    createData(spark, 10000)
    logger.info("Closing down Spark")
    spark.stop()
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

  private def prepareNamespace(spark: SparkSession, supportedCloud: AfonCloud): Unit = {
    val cloud = supportedCloud.toString()
    //    spark.sql(f"DROP SCHEMA IF EXISTS $cloud.$NAMESPACE")
    //    spark.sql(f"DROP NAMESPACE IF EXISTS $cloud.$NAMESPACE")
    spark.sql(f"CREATE NAMESPACE IF NOT EXISTS $cloud.$NAMESPACE")
  }

  private def initSpark(properties: AfonPropertyProvider): SparkSession = {
    new SparkClient(AWS, properties).sparkSessionAllWarehouses() // AWS for argument's sake...
  }
}
