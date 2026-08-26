package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.SparkHelper.{CatalogName1, CatalogName2, spark}
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper, SparkHelper}
import health.emrys.afon.cluster.PolarisConfiguration.polarisCredentials
import health.emrys.afon.config.{AfonPropertyProvider, AfonUser, AfonUserProvider}
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.polaris.catalogs.{AwsForbiddenPolarisCatalog, AwsPolarisCatalog}
import health.emrys.afon.polaris.config.PolarisCatalogClient
import org.apache.hc.core5.http.HttpResponse
import org.apache.iceberg.exceptions.ForbiddenException
import org.scalatest.GivenWhenThen

import java.util
import scala.jdk.CollectionConverters._

class OnboardingSpec extends SpecPretifier with GivenWhenThen {

  private val APP_PROPERTIES: AfonPropertyProvider = AcceptanceTestConfig.instance.getProperties
  val catalog: AwsForbiddenPolarisCatalog = createCatalogInPolaris(APP_PROPERTIES)
  val warehouse: String = catalog.catalogName()

  val sparkUser = SparkHelper.sparkWarehouse.getClientId

  "Users" should {
    "not see catalogs they should not" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(APP_PROPERTIES) != null)
      assert(ContainerHelper.instance.startPolaris(APP_PROPERTIES) != null)

      When(s"we configure the ${warehouse} with a catalog that looks like:\n$catalog")
      configureUsersInKeyCloakAndPolaris(warehouse, TestUsers())
      val sql = s"CREATE NAMESPACE IF NOT EXISTS ${warehouse}.test_namespace"
      When(s"we create a namespace with SQL '$sql'")
      thenUserIsNotAllowedToExecute(sql)
    }
  }

  private def thenUserIsNotAllowedToExecute(sql: String) = {

    Then(s"$sparkUser is not allowed to use this catalog")
    intercept[ForbiddenException] {
      spark.sql(sql)
    }
  }

  "Granting catalog permissions" should {
    val catalog1 = new AwsPolarisCatalog(APP_PROPERTIES) {
      override def catalogName(): String = CatalogName1
    }
    val catalog2 = new AwsPolarisCatalog(APP_PROPERTIES) {
      override def catalogName(): String = CatalogName2
    }

    val polarisClient = new PolarisCatalogClient(APP_PROPERTIES)

    val sql = s"CREATE NAMESPACE IF NOT EXISTS ${CatalogName1}.test_namespace"

    "prevent random users" in {
      Given(s"Catalogs $CatalogName1 and $CatalogName2")
      checkForFailures(polarisClient.configureCatalogs(
        polarisCredentials(APP_PROPERTIES),
        Array(catalog1, catalog2))
      )
      When("a user is not permissioned")
      thenUserIsNotAllowedToExecute(sql)
    }

    val user = new AfonUser(sparkUser, SparkHelper.sparkWarehouse.getClientSecret, sparkUser + "@test.com", "slug", "first", "second")

    "allow access" in {
      Given(s"we configure catalog $CatalogName1 for user $sparkUser")
      configureUsersInKeyCloakAndPolaris(CatalogName1, TestUsers(user))
      When(s"we execute $sql")
      Then("there are no errors")
      spark.sql(sql)
    }

    "not revoke access when the same user is added to another catalog" in { // this highlights the bug for ticket 117
      Given(s"we configure catalog $CatalogName2 for user $sparkUser")
      configureUsersInKeyCloakAndPolaris(CatalogName2, TestUsers(user))
      val sqlCatalog2 = s"CREATE NAMESPACE IF NOT EXISTS ${CatalogName2}.test_namespace"
      When(s"we execute $sqlCatalog2")
      Then("there are no errors")
      spark.sql(sqlCatalog2)
      And(s"we can still see the $CatalogName1 when we call $sql")
      spark.sql(sql)
    }
  }

  "Configuring the users" should {
    "be idempotent" in {
      Given(s"we already have a warehouse called $warehouse")
      When(s"we configure $warehouse again")
      Then("there are no errors")
      configureUsersInKeyCloakAndPolaris(warehouse, TestUsers())
    }
  }

  private def configureUsersInKeyCloakAndPolaris(warehouse: String, testUsers: AfonUserProvider): Unit = {
    val toTest = new ConfigureUsersMain(testUsers, APP_PROPERTIES)
    val responses: util.List[HttpResponse] = toTest.configureUsers(ContainerHelper.KEYCLOAK_HOSTNAME, warehouse)
    checkForFailures(responses)
  }

  private def checkForFailures(responses: util.List[HttpResponse]): Unit = {
    responses.asScala.foreach(r => {
      val httpCode = r.getCode()
      println(f"code = $httpCode, message = ${r.getReasonPhrase}")
      assert(httpCode / 100 == 2 || httpCode == 409) // KeyCloakConfigurer.KEYCLOAK_REALM hardcoded
    })
  }

  private def createCatalogInPolaris(properties: AfonPropertyProvider) = {
    val catalog = new AwsForbiddenPolarisCatalog(properties)
    new PolarisCatalogClient(properties).configureCatalogs(
      polarisCredentials(properties),
      Array(catalog)
    )
    catalog
  }
}
