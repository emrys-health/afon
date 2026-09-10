package health.emrys.afon.acceptance.users

import health.emrys.afon.acceptance.SparkHelper.{CatalogName1, CatalogName2, spark}
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.{Aws, Users}
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper, SparkHelper}
import health.emrys.afon.config.{AfonPropertyProvider, AfonUser, AfonUserProvider}
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.polaris.catalogs.{AwsForbiddenPolarisCatalog, AwsPolarisCatalog, PolarisCatalog}
import org.apache.hc.core5.http.HttpResponse
import org.apache.iceberg.exceptions.ForbiddenException
import org.scalatest.GivenWhenThen

import java.util
import scala.jdk.CollectionConverters._

@Aws
@Users
class UsersSpec extends SpecPretifier with GivenWhenThen {

  val catalog: PolarisCatalog = createAwsForbiddenPolarisCatalog()
  val catalogName: String = catalog.catalogName()

  val sparkUser = SparkHelper.sparkWarehouse.getClientId

  "Users" should {
    "not see catalogs they should not" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(AcceptanceTestConfig.instance.getProperties) != null)
      assert(ContainerHelper.instance.startPolaris(AcceptanceTestConfig.instance.getProperties) != null)

      When(s"we configure the ${catalogName} with a catalog that looks like:\n$catalog")
      configureUsersInKeyCloakAndPolaris(catalogName, TestUsers())
      val sql = s"CREATE NAMESPACE IF NOT EXISTS ${catalogName}.test_namespace"
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

  "Granting catalog privileges" should {
    val catalog1: PolarisCatalog = new AwsPolarisCatalog(AcceptanceTestConfig.instance.getProperties) {
      override def catalogName(): String = CatalogName1
    }
    val catalog2: PolarisCatalog = new AwsPolarisCatalog(AcceptanceTestConfig.instance.getProperties) {
      override def catalogName(): String = CatalogName2
    }

    val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

    val sql = s"CREATE NAMESPACE IF NOT EXISTS ${CatalogName1}.test_namespace"

    "prevent random users" in {
      Given(s"Catalogs $CatalogName1 and $CatalogName2")
      checkForFailures(catalogManager.configureCatalogs(catalog1, catalog2))
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
      Given(s"we already have a catalogName called $catalogName")
      When(s"we configure $catalogName again")
      Then("there are no errors")
      configureUsersInKeyCloakAndPolaris(catalogName, TestUsers())
    }
  }

  private def configureUsersInKeyCloakAndPolaris(catalogName: String, testUsers: AfonUserProvider): Unit = {
    val toTest = new ConfigureUsersMain(testUsers, AcceptanceTestConfig.instance.getProperties)
    val oidc: util.List[HttpResponse] = toTest.configureOidcUsers(ContainerHelper.KEYCLOAK_HOSTNAME)
    checkForFailures(oidc)
    val polaris: util.List[HttpResponse] = toTest.configurePolarisUsers(catalogName)
    checkForFailures(polaris)
  }

  private def checkForFailures(responses: util.List[HttpResponse]): Unit = {
    responses.asScala.foreach(r => {
      val httpCode = r.getCode()
      println(f"code = $httpCode, message = ${r.getReasonPhrase}")
      assert(httpCode / 100 == 2 || httpCode == 409) // KeyCloakConfigurer.KEYCLOAK_REALM hardcoded
    })
  }

  private def createAwsForbiddenPolarisCatalog() = {
    val catalog: PolarisCatalog = new AwsForbiddenPolarisCatalog(AcceptanceTestConfig.instance.getProperties)
    AcceptanceTestConfig.instance.getCatalogManager.configureCatalogs(catalog)
    catalog
  }
}
