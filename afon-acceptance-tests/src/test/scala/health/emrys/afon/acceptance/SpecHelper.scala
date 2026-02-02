package health.emrys.afon.acceptance

import SparkHelper.{namespace, sparkWarehouse}
import health.emrys.afon.config.AfonProperties
import uk.co.odinconsultants.documentation_utils.Datum

import java.sql.{Date, Timestamp}
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter

trait SpecHelper {
  private val simpleTableName: String = this.getClass.getSimpleName.replace("$", "_")

  val now: LocalDateTime = LocalDateTime.now
  val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddmmHHss")
  val formatted: String = now.format(formatter)

  val tableName: String = simpleTableName + formatted
  def cloudProvider: AfonProperties.SupportedCloudsEnum
  val tableNameFQN = cloudProvider + "." + namespace + "." + tableName

  def syntheticData(numRows: Int, numPartitions: Int = 4): Seq[Datum] = {
    Seq.range(0, numRows).map((i: Int) => Datum(
      i,
      s"label_$i",
      i % numPartitions,
      new Date(now.toEpochSecond(UTC) + (i * 3600 * 1000 * 24)),
      new Timestamp(now.toEpochSecond(UTC) + (i * 3600 * 1000)),
      i),
    )
  }
}
