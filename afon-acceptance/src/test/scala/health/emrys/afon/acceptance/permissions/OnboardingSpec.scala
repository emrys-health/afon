package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper}
import health.emrys.afon.ops.OnboardAllUsersMain
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class OnboardingSpec extends SpecPretifier with GivenWhenThen {

  "Users" should {
    val testUsers = new TestUsers()
    import testUsers._
    val properties = AcceptanceTestConfig.instance.getProperties
    val toTest = new OnboardAllUsersMain(userProvider, properties)
    "be configured concurrently for all clouds" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(properties) != null)
      assert(ContainerHelper.instance.startPolaris(properties) != null)

      val warehouse = "test1"
      When(s"we configure the users for catalog $warehouse")
      toTest.onboardAll(warehouse)
    }
  }

}
