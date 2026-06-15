package health.emrys.afon.acceptance.trino

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AcceptanceTestConfig, CatalogAdminGrant, CatalogRoles, SpecHelper}
import health.emrys.afon.config.AfonProperties
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS_TRINO
import health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.trino.SmokeTest
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class TrinoSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  val testHelper = AcceptanceTestConfig.instance

  "Trino" should {
    "vend credentials" in {
      val polaris = new PolarisConfigurer(testHelper.getPolarisClient)
      val trinoWarehouse = testHelper.getAwsTrino
      Given(s"a catalog called ${trinoWarehouse.warehouseName()}")
      polaris.configurCatalogFor(trinoWarehouse, testHelper.getAccessToken)
      val grantCatalogAdminFn = new CatalogAdminGrant(this, trinoWarehouse)
      val catalogRolesFn = new CatalogRoles(this, trinoWarehouse)
      grantCatalogAdminFn()
      catalogRolesFn()

      val tableName = tableNameFQN.toLowerCase()
      And(s"a table called $tableName")
      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS ${cloudProvider}.$ACCEPTANCE_TEST_NAMESPACE")
      spark.createDataFrame(syntheticData(100)).writeTo(tableName).create()

      val smokeTest = new SmokeTest(s"jdbc:trino://localhost:8080/local/${AWS_TRINO}")
      smokeTest.readTable(tableName)
    }
  }

  override def cloudProvider: AfonProperties.SupportedCloudsEnum = AWS_TRINO
}
