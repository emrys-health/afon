package health.emrys.afon.acceptance.aws

import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class ExternalGcpTableSpec  extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  val FEDERATED_CATALOG_NAME = "federatedCatalogName"

  "An external table" should {

  }

}
