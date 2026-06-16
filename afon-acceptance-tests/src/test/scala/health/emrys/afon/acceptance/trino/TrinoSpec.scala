package health.emrys.afon.acceptance.trino

import health.emrys.afon.acceptance.ContainerHelper.trinoLocalConfig
import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AcceptanceTestConfig, CatalogAdminGrant, CatalogRoles, ContainerHelper, SpecHelper}
import health.emrys.afon.config.{AfonProperties, AfonPropertiesLoader}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS_TRINO
import health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.trino.SmokeTest
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class TrinoSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val testHelper          = AcceptanceTestConfig.instance
  val trinoWarehouse      = testHelper.getAwsTrino
  val grantCatalogAdminFn = new CatalogAdminGrant(this, trinoWarehouse, noisy = false)
  val catalogRolesFn      = new CatalogRoles(this, trinoWarehouse, noisy = false)
  val properties          = new AfonPropertiesLoader().getProperties

  "Trino" should {
    "vend credentials" in {
      val polaris = new PolarisConfigurer(testHelper.getPolarisClient)

      Given(s"a catalog called ${trinoWarehouse.warehouseName()} in Polaris")
      polaris.configurCatalogFor(trinoWarehouse, testHelper.getAccessToken).foreach {
        response =>
          assert(response.getCode / 100 == 2)
      }

      grantCatalogAdminFn()
      catalogRolesFn()

      val tableName = tableNameFQN.toLowerCase()
      val nRows = 100
      And(s"a table called $tableName with $nRows rows in cloud storage")
      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS ${cloudProvider}.$ACCEPTANCE_TEST_NAMESPACE")
      spark.createDataFrame(syntheticData(nRows)).writeTo(tableName).create()

      And(s"a Trino instance with local.properties of:\n${trinoLocalConfig(properties, cloudProvider)}")

      val url = s"jdbc:trino://localhost:8080/local/${AWS_TRINO}"
      When(s"we connect to $url")
      val smokeTest = new SmokeTest(url)
      val sql = s"SELECT COUNT(*) FROM $tableName"
      And(s"execute SQL: ${formatSQL(sql)}")
      val result = smokeTest.readTable(sql)
      Then(s"the result is $result")
      assert(Integer.parseInt(result) == nRows)
    }
  }

  override def cloudProvider: AfonProperties.SupportedCloudsEnum = AWS_TRINO
}
