package health.emrys.afon.acceptance

import scala.io.Source
import scala.util.Try
import java.nio.file.Paths
import java.io.File

object FileHelper {

  def readFromRoot(file: String): String = {
    val tried = tryReading(file)
    tried.getOrElse(tryReading(".." + File.separator + file).get)
  }

  def tryReading(file: String): Try[String] = {
    Try {
      println(s"reading $file")
      val path = Paths.get(file)
      val source = Source.fromFile(path.toFile)
      val lines = source.getLines().mkString("\n")
      source.close()
      lines
    }
  }

  def main(args: Array[String]): Unit = {
    val file = "scripts/aws/create.sh"
    println(f"Smoke test\n" + readFromRoot(file))
  }

}
