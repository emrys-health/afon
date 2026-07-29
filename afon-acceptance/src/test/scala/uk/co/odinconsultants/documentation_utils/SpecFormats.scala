package uk.co.odinconsultants.documentation_utils

import com.github.vertical_blank.sqlformatter.SqlFormatter.format

trait SpecFormats {

  def classNameOf(all: Seq[_]): String = all.head.getClass.getSimpleName

  def inTheRangeOf(range: Range): String = s"in the range from ${range.start} to ${range.`end`}"

  def prettyPrintSampleOf[T](xs: Seq[T], sampleSize: Int = 3): String = {
    val sample: List[String] = alternativeColours(xs.take(sampleSize))
    val footer: String = if (xs.size > sampleSize) "..." else ""
    s"${indent(sample).mkString("\n")}$footer"
  }

  def indent[A](xs: Iterable[A]): Iterable[String] = xs.map((x: A) => s"\t\t\t$x")

  val scenarioDelimiter = s"\n${"+ " * 20}\n+\n"

  def formatSQL(sql: String): String = s"\n${Console.YELLOW}${format(sql)}${Console.RESET}"

  def toHumanReadable[T](rows: Seq[T]): String = alternativeColours(rows).mkString("\n")

  def alternativeColours[T](xs: Seq[T]): List[String] = {
    xs.zipWithIndex.map { case (x: T, index: Int) =>
      val colour = if (index % 2 == 0) Console.YELLOW else Console.MAGENTA
      s"$colour$x"
    }.toList :+ s"${Console.RESET}"
  }

  def emphasise(x: String): String = s"${Console.BOLD}$x${Console.RESET}"

  def truncatingUpto(msg: String, x: String): String = s"$x${msg.substring(msg.indexOf(x))}"

  def delimiter(n: Int): String = {
    val colours = List(
      Console.MAGENTA,
      Console.YELLOW,
      Console.WHITE,
      Console.CYAN,
      Console.BLUE,
      Console.RED,
      Console.GREEN,
    )
    (0 to n)
      .map { n =>
        s"${colours(n % colours.length)}+ "
      }
      .mkString("") + Console.RESET
  }

  def histogram[K, V](kv: Map[K, V], names: Seq[String]): String = {
    val width = (kv.keys.map(_.toString.length) ++ names.map(_.length)).max + 2
    Seq(columnNames(names, width), histogramValues(kv, width)).mkString("\n")
  }

  def columnNames(names: Seq[String], width: Int = 20): String = {
    val cols  = names.map(x => s"%-${width}s".format(x)).mkString("")
    val lines = names.map(x => s"%-${width}s".format("-" * x.length)).mkString("")
    Seq(cols, lines).mkString("\n")
  }

  def histogramValues[K, V](kv: Map[K, V], width: Int = 20): String =
    kv.map { case (k, v) => s"%-${width}s%s".format(k, v) }.mkString("\n")

}

