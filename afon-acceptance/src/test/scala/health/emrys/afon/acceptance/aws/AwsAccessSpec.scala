package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.ContainerHelper.{trinoConfigProperties, trinoLocalConfig}
import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Aws}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.config.AfonCloud.AWS
import health.emrys.afon.ops.smoke.TrinoQuerySmoke
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Aws
@Access
class AwsAccessSpec extends AbstractAccessSpec {

  override def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(AWS);

  override def cloudProvider: AfonCloud = AWS

  "Trino" should {
    "vend credentials" in {
      Given(s"a catalog called ${catalog.catalogName()} in Polaris")

      val tableName = tableNameFQN.toLowerCase()
      And(s"a table called $tableName with $NUM_ROWS rows in cloud storage")

      And(
        s"""a Trino instance with $cloudProvider.properties of:
           |
           |${trinoLocalConfig(properties, cloudProvider)}""".stripMargin)

      var trinoHttp = trinoConfigProperties().get("discovery.uri").toString
      trinoHttp = trinoHttp.substring(trinoHttp.indexOf("//") + 2)
      val url = s"jdbc:trino://${trinoHttp}/"
      When(s"we connect to $url")
      val smokeTest = new TrinoQuerySmoke(url)
      val sql = s"SELECT COUNT(*) FROM $tableName"
      And(s"execute SQL: ${formatSQL(sql)}")
      val result = smokeTest.query(sql)
      Then(s"the result is $result")
      assert(Integer.parseInt(result) == NUM_ROWS)
    }
  }

}
