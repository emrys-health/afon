package health.emrys.afon.acceptance

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.polaris.comms.PolarisConfigurer._
import org.apache.polaris.core.admin.model.CatalogPrivilege
import org.apache.polaris.core.admin.model.CatalogPrivilege.{TABLE_READ_DATA, TABLE_WRITE_DATA}
import org.scalatest.GivenWhenThen

abstract class PolarisFixture extends Function0[Any] {

  val REST_CODE_RESOURCE_CREATED = 201

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

}

/**
 * Without this, you'll see something like:
 * org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Principal 'root'
 * with activated PrincipalRoles '[service_admin]' and activated grants via
 * '[service_admin, catalog_admin]' is not authorized for op
 * CREATE_TABLE_STAGED_WITH_WRITE_DELEGATION
 */
class CatalogAdminGrant(test: GivenWhenThen,
                        config: CloudConfigForPolaris,
                        warehouseNameOpt: Option[String] = None) extends PolarisFixture {
  import test._

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

class CatalogRoles(test: GivenWhenThen,
                   config: CloudConfigForPolaris,
                   warehouseNameOpt: Option[String] = None) extends PolarisFixture {
  import test._

  val catalogRoleName = "mycatalogrole"
  val principalRoleName = "myprinciplerole"
  val principleName = "myprincipleName"

  def apply = {
    Given(s"a Polaris endpoint at ${client.getEndpoint}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())
//    assert(createPrincipal().getCode == REST_CODE_RESOURCE_CREATED)
    createPrincipalDelegating()

    assert(createPrincipalRoles().getCode == REST_CODE_RESOURCE_CREATED)

    assert(createCatalogRole(warehouseName).getCode == REST_CODE_RESOURCE_CREATED)
    assert(createCatalogRoleGrant(warehouseName, TABLE_READ_DATA).getCode == REST_CODE_RESOURCE_CREATED)
    assert(createCatalogRoleGrant(warehouseName, TABLE_WRITE_DATA).getCode == REST_CODE_RESOURCE_CREATED)

    assert(associateRoles(warehouseName).getCode == REST_CODE_RESOURCE_CREATED)
    assert(associatePrincipleRoles().getCode == REST_CODE_RESOURCE_CREATED)

    Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

  private def associatePrincipleRoles() = {
    val uri = getPrincipleToRolesURI(principleName)
    val json = config.principalRoleCreation(principalRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.putJsonCall(uri, json, accessToken)
  }

  private def associateRoles(warehouseName: String) = {
    val uri = getCatalogRolesURI(principalRoleName, warehouseName)
    val json = config.catalogRoleCreation(catalogRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.putJsonCall(uri, json, accessToken)
  }

  private def createCatalogRoleGrant(warehouseName: String,
                                     perm: CatalogPrivilege) = {
    val uri = catalogRolesGrantURI(warehouseName, catalogRoleName)
    val json = config.catalogRoleGrant(perm)
    When(s"we call '$uri' with payload:\n$json")
    client.putJsonCall(uri, json, accessToken)
  }
  private def createCatalogRole(warehouseName: String) = {
    val uri = catalogRolesURI(warehouseName)
    val json = config.catalogRoleCreation(catalogRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.postJsonCall(uri, json, accessToken)
  }
  private def createPrincipalRoles() = {
    val uri = principalRolesURI
    val json = config.principalRoleCreation(principalRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.postJsonCall(uri, json, accessToken)
  }
  private def createPrincipalDelegating() = {
    val configurer = new PolarisConfigurer(client)
    val principle = configurer.configureUser(config, accessToken, principleName)
    println("principle = " + principle)
    principle
  }
}