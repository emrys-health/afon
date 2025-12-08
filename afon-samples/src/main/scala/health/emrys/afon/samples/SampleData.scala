package health.emrys.afon.samples

import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.util.Random

object SampleData {

  val MAX_AGE = 100

  def patientDataset(spark: SparkSession, n: Int): DataFrame =
    spark.createDataFrame(Range(0, n).map(i => Patient(i, i % MAX_AGE)))

  def occupancyDataset(spark: SparkSession, n: Int): DataFrame =
    spark.createDataFrame(Range(0, n).map(i => Occupancy(i, Random.nextInt((i % MAX_AGE) + 1))))

}
