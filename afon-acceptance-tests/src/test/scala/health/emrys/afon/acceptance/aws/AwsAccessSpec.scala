package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.{namespace, spark, sparkWarehouse}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier


class AwsAccessSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val NUM_ROWS = 100

  val FULL_NAMESPACE = s"${sparkWarehouse.getSparkCatalog}.$namespace"

  s"A table" should {
    s"be created and populated with $NUM_ROWS rows" in {
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      Given(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      And(s"we write $NUM_ROWS rows of data to a table in that namespace called '$tableName'")
      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
      Then(s"when we read the table, we find $NUM_ROWS rows of data")
      val output = spark.read.table(tableNameFQN)
      assert(output.collect().size == NUM_ROWS)
    }
  }
}
