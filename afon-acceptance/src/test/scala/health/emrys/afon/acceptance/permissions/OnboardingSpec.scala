package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper, SparkHelper}
import health.emrys.afon.cluster.PolarisConfiguration
import health.emrys.afon.config.AfonPropertyProvider
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog
import health.emrys.afon.polaris.config.PolarisCatalogClient
import org.apache.hc.core5.http.HttpResponse
import org.scalatest.GivenWhenThen

import java.util
import scala.jdk.CollectionConverters._

class OnboardingSpec extends SpecPretifier with GivenWhenThen {

  val testUsers = new TestUsers()
  import testUsers._

  private val APP_PROPERTIES: AfonPropertyProvider = AcceptanceTestConfig.instance.getProperties
  val catalog: AwsForbiddenPolarisCatalog = createCatalogInPolaris(APP_PROPERTIES)
  val warehouse: String = catalog.catalogName()

  "Users" should {
    "not see catalogs they should not" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(APP_PROPERTIES) != null)
      assert(ContainerHelper.instance.startPolaris(APP_PROPERTIES) != null)

      When(s"we configure the ${warehouse} with a catalog that looks like:\n$catalog")
      configureUsersInKeyCloakAndPolaris(warehouse)
      val sql = s"CREATE NAMESPACE IF NOT EXISTS ${warehouse}.test_namespace"
      When(s"we create a namespace with SQL '$sql'")
      val sparkUser = SparkHelper.sparkWarehouse.getClientId
      Then(s"$sparkUser is not allowed to use this catalog")
      intercept[org.apache.iceberg.exceptions.ForbiddenException] {
        spark.sql(sql)
      }
    }
  }

  "Configuring the users" should {
    "be idempotent" in {
      Given(s"we already have a warehouse called $warehouse")
      When(s"we configure $warehouse again")
      Then("there are no errors")
      configureUsersInKeyCloakAndPolaris(warehouse)
    }
  }

  private def configureUsersInKeyCloakAndPolaris(warehouse: String): Unit = {
    val toTest = new ConfigureUsersMain(userProvider, APP_PROPERTIES)
    val responses: util.List[HttpResponse] = toTest.configureUsers(ContainerHelper.KEYCLOAK_HOSTNAME, warehouse)
    responses.asScala.foreach(r => {
      println(f"code = ${r.getCode}, message = ${r.getReasonPhrase}")
      val httpCode = r.getCode()
      assert(httpCode / 100 == 2 || httpCode == 409) // KeyCloakConfigurer.KEYCLOAK_REALM hardcoded
    })
  }

  private def createCatalogInPolaris(properties: AfonPropertyProvider) = {
    val catalog = new AwsForbiddenPolarisCatalog(properties)
    new PolarisCatalogClient(properties).configureCatalogs(
      PolarisConfiguration.polarisCredentials(properties),
      Array(catalog)
    )
    catalog
  }
}
