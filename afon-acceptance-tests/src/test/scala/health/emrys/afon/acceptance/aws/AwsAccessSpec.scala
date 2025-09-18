package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.{namespace, sparkWarehouse, spark}
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.{Datum, SimpleFixture, SpecPretifier}

import java.sql.{Date, Timestamp}
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter


class AwsAccessSpec extends SpecPretifier with GivenWhenThen {

  val NUM_ROWS = 100
  val NUM_PARTITIONS = 4

  val now: LocalDateTime = LocalDateTime.now
  val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddmmHHss")
  val formatted: String = now.format(formatter)

  val tableName = sparkWarehouse.getSparkCatalog + "." + namespace + ".AwsAccessSpec" + formatted

  def syntheticData(): Seq[Datum] = Seq.range(0, NUM_ROWS).map((i: Int) => Datum(
    i,
    s"label_$i",
    i % NUM_PARTITIONS,
    new Date(now.toEpochSecond(UTC) + (i * 3600 * 1000 * 24)),
    new Timestamp(now.toEpochSecond(UTC) + (i * 3600 * 1000 )))
  )

  val FULL_NAMESPACE = s"${sparkWarehouse.getSparkCatalog}.$namespace"

  s"A table" should {
    s"be created called $tableName and populated with $NUM_ROWS rows" in /*new SimpleFixture*/ {
//      override val spark: SparkSession = spark
//      override def dataDir(tableName: String): String = ???
      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE")
      spark.createDataFrame(syntheticData()).writeTo(tableName).create()
    }
    s"that has been written with $NUM_ROWS when read should have the same number of rows" in {
      val output = spark.read.table(tableName)
      assert(output.collect().size == NUM_ROWS)
    }
  }
}
