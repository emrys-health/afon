package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper}
import health.emrys.afon.ops.ConfigureKeyCloakUsersMain
import org.scalatest.GivenWhenThen

class OnboardingSpec extends SpecPretifier with GivenWhenThen {

  "Users" should {
    val testUsers = new TestUsers()
    import testUsers._
    val properties  = AcceptanceTestConfig.instance.getProperties
    val toTest      = new ConfigureKeyCloakUsersMain(userProvider, properties)
    "be configured concurrently for all clouds" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(properties) != null)
      assert(ContainerHelper.instance.startPolaris(properties) != null)

      val warehouse = "test1"
      When(s"we configure the users for catalog $warehouse")
      toTest.configureUsers(ContainerHelper.KEYCLOAK_HOSTNAME, warehouse)
    }
  }

}
