package uk.co.odinconsultants.documentation_utils

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

trait SpecPretifier extends AnyWordSpec with BeforeAndAfterAll with BeforeAndAfterEach  with SpecFormats {


  override def beforeAll(): Unit = delimit(delimiter(50))

  override def afterEach(): Unit = delimit(delimiter(50))

  def delimit(x: String): Unit = info(x)

}
