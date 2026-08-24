package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog
import health.emrys.afon.polaris.config.PolarisConfigurer.catalogsURI
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

abstract class AbstractAccessForbiddenSpec extends SpecPretifier with GivenWhenThen with SpecHelper {

  def config: PolarisCatalog

  override def cloudProvider: AfonCloud

  val client = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken
  val GrantCatalogAdmin = new CatalogAdminGrant(this, config)
  val CatalogRoles = new CatalogRoles(this, config)
  val NAMESPACE = "forbidden"
  val FULL_NAMESPACE = s"$cloudProvider.$NAMESPACE"
  val NUM_ROWS = 10

  "Polaris" should {
    "be able to host a catalog that has a bucket it does not have access to" in {
      val uri = catalogsURI
      Given(s"an admin has set up the environment with:\n" + readEnvSetup())
      val json = config.catalogConfig
      When(s"we call $uri with payload:\n$json")
      client.post(uri, json, accessToken)
      val uriGet = s"/api/catalog/v1/config?warehouse=${config.catalogName()}"
      Then(s"Polaris acknowledges the catalog when we call $uriGet")
      val response = client.get(uriGet, accessToken)
      assert(response.getCode == 200)
    }


    "be told about the catalog admin grants" in GrantCatalogAdmin()

    "be told about the catalog roles" in CatalogRoles()
  }

  def readEnvSetup(): String

  s"A table that belongs to a forbidden bucket" should {
    "be denied access when we write to it" in {
      val sql = s"CREATE NAMESPACE IF NOT EXISTS $FULL_NAMESPACE"
      Given(s"we create a namespace with SQL '$sql'")
      spark.sql(sql)
      val uri = s"/api/catalog/v1/${config.catalogName()}/namespaces/$NAMESPACE"
      And(s"Polaris acknowledges the namespace exists when call '$uri''")
      val response = client.get(uri, accessToken)
      assert(response.getCode == 200)
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
