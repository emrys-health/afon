package health.emrys.afon.acceptance.aws

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import health.emrys.afon.polaris.comms.PolarisConfigurer.getCatalogAdminGrantsURI
import org.scalatest.GivenWhenThen

class CatalogAdminGrant(test: GivenWhenThen,
                        config: CloudConfigForPolaris,
                        warehouseNameOpt: Option[String] = None) extends Function0[Any] {

  import test._

  val REST_CODE_RESOURCE_CREATED = 201

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  override def apply(): Any = {
    Given(s"a Polaris endpoint at ${client.getEndpoint}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())
    val uri  = getCatalogAdminGrantsURI(warehouseName)
    val json = config.catalogAdminGrants
    When(s"we call '$uri' with payload:\n$json")
    val response = client.putJsonCall(uri, json, accessToken)
    Then(s"Polaris indicates the HTTP request lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
    assert(response.getCode == REST_CODE_RESOURCE_CREATED)
  }
}
