package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper}
import health.emrys.afon.cluster.PolarisConfiguration
import health.emrys.afon.config.AfonProperty.USER_POLARIS_ID
import health.emrys.afon.config.AfonPropertyProvider
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog
import health.emrys.afon.polaris.config.PolarisCatalogClient
import org.apache.hc.core5.http.HttpResponse
import org.scalatest.GivenWhenThen

import java.util
import java.util.{ArrayList, List}
import scala.jdk.CollectionConverters._

class OnboardingSpec extends SpecPretifier with GivenWhenThen {


  val testUsers = new TestUsers()
  import testUsers._

  private val APP_PROPERTIES: AfonPropertyProvider = AcceptanceTestConfig.instance.getProperties

  "Users" should {
    val properties = APP_PROPERTIES

    "be configured concurrently for all clouds" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(properties) != null)
      assert(ContainerHelper.instance.startPolaris(properties) != null)
      val catalog: AwsForbiddenPolarisCatalog = createCatalogInPolaris(properties)
      val warehouse = catalog.catalogName()
      When(s"we configure the users for catalog ${warehouse}")
      configureUsersInKeyCloakAndPolaris(warehouse)
      val sql = s"CREATE NAMESPACE IF NOT EXISTS ${warehouse}.test_namespace"
      When(s"we create a namespace with SQL '$sql'")
      Then(s"${USER_POLARIS_ID.getValueFrom(APP_PROPERTIES.getProperties)} is not allowed to see it")
      intercept[org.apache.iceberg.exceptions.ForbiddenException] {
        spark.sql(sql)
      }
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
