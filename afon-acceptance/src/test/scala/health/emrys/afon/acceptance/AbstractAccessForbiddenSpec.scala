package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.polaris.catalogs.PolarisCatalog
import org.scalatest.GivenWhenThen

abstract class AbstractAccessForbiddenSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  def catalog: PolarisCatalog

  override def catalogProvider(): Any

  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager
  val grantCatalogAdmin = new GrantCatalogAdmin(this, catalog)
  val createCatalogRole = new CreateCatalogRole(this, catalog)
  val NAMESPACE = "forbidden"
  val FULL_NAMESPACE = s"$catalogProvider.$NAMESPACE"
  val NUM_ROWS = 10

  "Polaris" should {
    "be able to host a catalog that has a bucket it does not have access to" in {
      Given(s"an admin has set up the environment with: " + envSetupScript())
      val json = catalog.catalogConfig
      When(s"we configure the catalog with payload:\n$json")
      catalogManager.createCatalogIfAbsent(catalog)
      val uriGet = s"/api/catalog/v1/config?warehouse=${catalog.catalogName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = catalogManager.getCatalogConfig(catalog.catalogName())
      assert(response.getCode == 200)
    }


    "be told about the catalog admin grants" in grantCatalogAdmin()

    "be told about the catalog roles" in createCatalogRole()
  }

  def envSetupScript(): String

  s"A table that belongs to a forbidden bucket" should {
    "be denied access when we write to it" in {
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      Given(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      And(s"Polaris acknowledges the namespace exists")
      val nsResponse = catalogManager.getNamespace(catalog.catalogName(), NAMESPACE)
      assert(nsResponse.getCode == 200)
      val fqn = s"$FULL_NAMESPACE.$tableName"
      When(s"we write $NUM_ROWS rows of data to a table in that namespace called '$fqn'")
      val expectedX = createTableAndPopulate(fqn)
      Then(
        s"""an exception (${expectedX.getClass.getName}) is thrown because access is forbidden.
           |Cause is ${expectedX.getCause}
           |""".stripMargin)
    }
  }

  protected def createTableAndPopulate(fqn: String): Exception = {
    intercept[org.apache.spark.SparkException] {
      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(fqn).create()
    }
  }
}
