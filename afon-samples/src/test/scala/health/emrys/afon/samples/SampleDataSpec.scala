package health.emrys.afon.samples

import health.emrys.afon.samples.SampleData.{occupancyDataset, patientDataset}
import org.apache.spark.SparkConf
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.linalg.Matrix
import org.apache.spark.ml.stat.Correlation
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.GivenWhenThen
import org.scalatest.wordspec.AnyWordSpec

/**
 * See Python and Scala code at https://spark.apache.org/docs/latest/ml-features.html#vectorassembler
 */
class SampleDataSpec extends AnyWordSpec with GivenWhenThen {

  val spark = SparkSession.builder().config(new SparkConf().setMaster("local[2]").setAppName("Tests")).getOrCreate()
  val NUM_PATIENTS = 10000

  "The sample data that links age to occupancy" should {
    "contain a signal in the noise that is detectable" in {
      val dfPatients = patientDataset(spark, NUM_PATIENTS)
      val dfOccupancy = occupancyDataset(spark, NUM_PATIENTS)
      val dfAll = dfPatients.join(dfOccupancy, "id")
      assert(dfAll.count() == NUM_PATIENTS)

      val featuresColName = "features"
      val assembler = new VectorAssembler()
        .setInputCols(Array("age", "numDays"))
        .setOutputCol(featuresColName)

      val data = assembler.transform(dfAll)
      val Row(coeff1: Matrix) = Correlation.corr(data, featuresColName).head()
      println(s"Pearson correlation matrix:\n $coeff1")
      assert(coeff1(0, 0) == 1.0)
      assert(coeff1(1, 1) == 1.0)
      assert(coeff1(0, 1) > 0.6)
      assert(coeff1(1, 0) > 0.6)
    }
  }

}
