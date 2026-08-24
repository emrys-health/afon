package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.{AcceptanceTestConfig, ContainerHelper}
import health.emrys.afon.cluster.PolarisConfiguration
import health.emrys.afon.ops.ConfigureUsersMain
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog
import health.emrys.afon.polaris.config.PolarisCatalogClient
import org.apache.hc.core5.http.HttpResponse
import org.scalatest.GivenWhenThen

import java.util
import java.util.{ArrayList, List}
import scala.jdk.CollectionConverters._

class OnboardingSpec extends SpecPretifier with GivenWhenThen {

  val NAMESPACE = "namespace_ph"
  "Users" should {
    val testUsers = new TestUsers()
    import testUsers._
    val properties = AcceptanceTestConfig.instance.getProperties
    val toTest = new ConfigureUsersMain(userProvider, properties)
    "be configured concurrently for all clouds" in {
      Given("A KeyCloak and Polaris instance")
      assert(ContainerHelper.instance.startKeyCloak(properties) != null)
      assert(ContainerHelper.instance.startPolaris(properties) != null)


      new PolarisCatalogClient(properties).configureCatalogs(
        PolarisConfiguration.polarisCredentials(properties),
        Array(new AwsForbiddenPolarisCatalog(properties))
      )

      val warehouse = AwsForbiddenPolarisCatalog.AWS_POLARIS_FORBIDDEN
      When(s"we configure the users for catalog ${warehouse}")
      val responses: java.util.List[HttpResponse] = toTest.configureUsers(ContainerHelper.KEYCLOAK_HOSTNAME, warehouse)
//      val responses: util.List[HttpResponse] = new util.ArrayList[HttpResponse]
//      responses.addAll(toTest.configureKeyCloak(ContainerHelper.KEYCLOAK_HOSTNAME))
//      responses.addAll(toTest.configurePolaris(warehouse))
      responses.asScala.foreach(r => {
        println(f"code = ${r.getCode}, message = ${r.getReasonPhrase}")
        val httpCode = r.getCode()
        assert(httpCode / 100 == 2 || httpCode == 409) // KeyCloakConfigurer.KEYCLOAK_REALM hardcoded
      })

      val sql = s"CREATE NAMESPACE IF NOT EXISTS $NAMESPACE"
      When(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)

      //      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).create()
    }
  }

}
