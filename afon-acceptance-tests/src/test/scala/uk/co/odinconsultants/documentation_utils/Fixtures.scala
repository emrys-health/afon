package uk.co.odinconsultants.documentation_utils

import org.apache.spark.sql.{Dataset, SparkSession}

import java.io.ByteArrayOutputStream
import java.nio.file.{Path, Paths}
import java.sql.Timestamp
import scala.annotation.tailrec
import java.sql.Date
import java.util.TimeZone
import scala.util.Random

case class Datum(id: Int, label: String, partitionKey: Long, date: Date, timestamp: Timestamp, randomInt: Int) {
  def toInsertSubclause: String = s"(${id}, '${label}', ${partitionKey}, cast(date_format('${date}', 'yyyy-MM-dd') as date), cast(date_format('${timestamp}', 'yyyy-MM-dd HH:mm:ss.SSS') as timestamp))"
}

trait Fixture[T] {
  def data: Seq[T]
}

trait TableNameFixture {
  val tableName = this.getClass.getSimpleName.replace("$", "_")
}

trait SimpleFixture extends Fixture[Datum] {

  import SimpleFixture.now

  val DayMS: Long = 24 * 60 * 60 * 1000

  def num_partitions: Int = 5

  def num_rows: Int = 20

  val spark: SparkSession

  def dataDir(tableName: String): String

  val today = new Date((now.getTime / DayMS).toLong * DayMS)
  val tsDelta: Long = 200
  val dayDelta: Int = -1

  def createData(num_partitions: Int, now: Date, dayDelta: Int, tsDelta: Long, num_rows: Int = num_rows): Seq[Datum] = {
    val today = new Date((now.getTime / DayMS).toLong * DayMS)
    val random = new Random()
    Seq.range(0, num_rows).map((i: Int) => Datum(
      i,
      s"label_$i",
      i % num_partitions,
      new Date(today.getTime + (i * DayMS * dayDelta)),
      new Timestamp(now.getTime + (i * tsDelta)),
      random.nextInt()
    )
    )
  }

  val data: Seq[Datum] = createData(num_partitions, now, dayDelta, tsDelta)

  def assertDataIn(tableName: String) = {
    import spark.implicits._
    val output: Dataset[Datum] = spark.read.table(tableName).as[Datum]
    assert(output.collect().toSet == data.toSet)
  }

  def dataFilesIn(tableName: String): List[String] = {
    val dir: String                                                      = dataDir(tableName)
    @tailrec
    def recursiveSearch(acc: Seq[String], paths: Seq[Path]): Seq[String] =
      if (paths.isEmpty) {
        acc
      } else {
        val current: Path   = paths.head
        val rest: Seq[Path] = paths.tail
        if (current.toFile.isDirectory) {
          recursiveSearch(acc, rest ++ current.toFile.listFiles().map(_.toPath))
        } else {
          recursiveSearch(acc :+ current.toString, rest)
        }
      }
    recursiveSearch(Seq.empty[String], Seq(Paths.get(dir))).toList
  }

  @tailrec
  final def inYnotX(xs: Seq[Datum], ys: Seq[Datum], diffs: Seq[Datum]): Seq[Datum] =
    if (xs.isEmpty) {
      diffs ++ ys
    } else if (ys.isEmpty) {
      diffs
    } else {
      if (xs.head == ys.head) {
        inYnotX(xs.tail, ys.tail, diffs)
      } else {
        inYnotX(xs, ys.tail, diffs :+ ys.head)
      }
    }

  implicit val comparison = new Ordering[Datum] {
    override def compare(
                          x: Datum,
                          y: Datum,
                        ): Int =
      (x.id - y.id) + (x.label.hashCode - y.label.hashCode) + (x.partitionKey - y.partitionKey).toInt
  }

  def diffHavingOrdered(other: Seq[Datum]): Seq[Datum] = {
    val sortedData          = data.sorted
    val sortedOther         = other.sorted
    val inData              = inYnotX(sortedData, sortedOther, Seq.empty)
    val inOther             = inYnotX(sortedOther, sortedData, Seq.empty)
    val diffs               = inData ++ inOther
    assert(diffs.isEmpty)
    diffs
  }

  def captureOutputOf[T](thunk: => T): String = {
    val out = new ByteArrayOutputStream()
    Console.withOut(out) {
      thunk
    }
    new String(out.toByteArray)
  }
}

object SimpleFixture {

  TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

  val now = new Date(new java.util.Date().getTime)
}
