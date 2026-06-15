package health.emrys.afon.acceptance.trino

import health.emrys.afon.acceptance.AcceptanceTestConfig
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class TrinoSpec extends SpecPretifier with GivenWhenThen  {

  val testHelper = AcceptanceTestConfig.instance

  "Trino" should {
    "vend credentials" in {
      val polaris = new PolarisConfigurer(testHelper.getPolarisClient)
      val trinoWarehouse = testHelper.getAwsTrino
      Given(s"a catalog called ${trinoWarehouse.warehouseName()}")
      polaris.configurCatalogFor(trinoWarehouse, testHelper.getAccessToken)
      println("test")
    }
  }

}
