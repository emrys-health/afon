package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.ContainerHelper.{KEYCLOAK_HOSTNAME, trinoConfigProperties, trinoLocalConfig}
import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Aws}
import health.emrys.afon.ops.smoke.TrinoQuerySmoke
import health.emrys.afon.config.FileUserProvider
import health.emrys.afon.polaris.catalogs.PolarisCatalog
import health.emrys.afon.security.keycloak.KeyCloakProvider

@Aws
@Access
class AwsAccessSpec extends AbstractAccessSpec {

  private lazy val AWS = AcceptanceTestConfig.instance.afonCatalogs().aws()

  override def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(AWS);

  override def afonCatalog(): Any = AWS

  "Trino" should {
    "vend credentials" in {
      Given(s"a catalog called ${catalog.catalogName()} in Polaris")

      val tableName = tableNameFQN.toLowerCase()
      And(s"a table called $tableName with $NUM_ROWS rows in cloud storage")

      And(
        s"""a Trino instance with $afonCatalog.properties of:
           |
           |${trinoLocalConfig(properties, afonCatalog.toString)}""".stripMargin)

      var trinoHttp = trinoConfigProperties().get("discovery.uri").toString
      trinoHttp = trinoHttp.substring(trinoHttp.indexOf("//") + 2)
      val url = s"jdbc:trino://${trinoHttp}/"
      When(s"we connect to $url")
      val user = new FileUserProvider().getConfiguredUser(properties)
      And(s"pass the token of user '${user.username()}', with which Trino calls Polaris")
      val token = new KeyCloakProvider(KEYCLOAK_HOSTNAME).userToken(user.username(), user.password())
      val smokeTest = new TrinoQuerySmoke(url, user, token)
      val sql = s"SELECT COUNT(*) FROM $tableName"
      And(s"execute SQL: ${formatSQL(sql)}")
      val result = smokeTest.query(sql)
      Then(s"the result is $result")
      assert(Integer.parseInt(result) == NUM_ROWS)
    }
  }

}
