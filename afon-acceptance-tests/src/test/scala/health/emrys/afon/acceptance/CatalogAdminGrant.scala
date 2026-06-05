package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.PolarisHelper.principalRoleName
import health.emrys.afon.polaris.catalogs.{CloudConfigForPolaris, JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.PolarisConfigurer._
import org.apache.polaris.core.admin.model.CatalogPrivilege
import org.apache.polaris.core.admin.model.CatalogPrivilege.{NAMESPACE_CREATE, NAMESPACE_FULL_METADATA, TABLE_CREATE, TABLE_READ_DATA, TABLE_WRITE_DATA, VIEW_FULL_METADATA}
import org.scalatest.GivenWhenThen

abstract class PolarisFixture extends Function0[Any] {

  val REST_CODE_RESOURCE_CREATED = 201

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()
  val dtos = new PolarisDTOs(new JsonMaker())

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
    val uri = getCatalogAdminGrantsURI(warehouseName)
    val json = dtos.catalogAdminGrants
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


  def apply = {
    Given(s"a Polaris endpoint at ${client.getEndpoint}")
    And(s"a principal that's already been created:\n${PolarisHelper.principle.getPrincipal}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())

    assert(createCatalogRole(warehouseName).getCode == REST_CODE_RESOURCE_CREATED)

    val privileges = Set(TABLE_READ_DATA, TABLE_WRITE_DATA, NAMESPACE_CREATE, TABLE_CREATE, NAMESPACE_FULL_METADATA)
    privileges.foreach(privilege =>
      assert(createCatalogRoleGrant(warehouseName, privilege).getCode == REST_CODE_RESOURCE_CREATED)
    )

    assert(associateRoles(warehouseName).getCode == REST_CODE_RESOURCE_CREATED)

    Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

  private def associateRoles(warehouseName: String) = {
    val uri = associateRolesURI(principalRoleName, warehouseName)
    val json = dtos.catalogRoleCreation(catalogRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.putJsonCall(uri, json, accessToken)
  }

  private def createCatalogRoleGrant(warehouseName: String,
                                     perm: CatalogPrivilege) = {
    val uri = catalogRolesGrantURI(warehouseName, catalogRoleName)
    val json = dtos.catalogRoleGrant(perm)
    When(s"we call '$uri' with payload:\n$json")
    client.putJsonCall(uri, json, accessToken)
  }

  private def createCatalogRole(warehouseName: String) = {
    val uri = catalogRolesURI(warehouseName)
    val json = dtos.catalogRoleCreation(catalogRoleName)
    When(s"we call '$uri' with payload:\n$json")
    client.postJsonCall(uri, json, accessToken)
  }

}