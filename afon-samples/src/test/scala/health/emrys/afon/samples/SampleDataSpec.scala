package health.emrys.afon.samples

import health.emrys.afon.samples.SampleData.{occupancyDataset, patientDataset}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import org.scalatest.wordspec.AnyWordSpec

class SampleDataSpec extends AnyWordSpec with GivenWhenThen {

  val spark = SparkSession.builder().config(new SparkConf().setMaster("local[2]").setAppName("Tests")).getOrCreate()
  val NUM_PATIENTS = 10000

  "The sample data" should {
    "contain signal in the noise" in {
      val dfPatients = patientDataset(spark, NUM_PATIENTS)
      val dfOccupancy = occupancyDataset(spark, NUM_PATIENTS)
      val dfAll = dfPatients.join(dfOccupancy, "id")
      assert(dfAll.count() == NUM_PATIENTS)
      // TODO do some ML work
    }
  }

}
