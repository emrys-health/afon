package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.PolarisHelper.principalRoleName
import health.emrys.afon.polaris.catalogs.{CloudConfigForPolaris, JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.PermissionsConfigurer
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
  val permissions = new PermissionsConfigurer(client)

  def apply = {
    Given(s"a Polaris endpoint at ${client.getEndpoint}")
    And(s"a principal that's already been created:\n${PolarisHelper.principle.getPrincipal}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())

    When(s"we create a catalog role name called '$catalogRoleName'")
    assert(permissions
      .createCatalogRole(warehouseName, catalogRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    val privileges = Set(TABLE_READ_DATA, TABLE_WRITE_DATA, NAMESPACE_CREATE, TABLE_CREATE, NAMESPACE_FULL_METADATA)
    When(s"we add to the catalog role '$catalogRoleName' permissions:\n${privileges.mkString("\n")}")
    privileges.foreach(privilege =>
      assert(permissions
        .createCatalogRoleGrant(warehouseName, privilege, catalogRoleName)
        .getCode == REST_CODE_RESOURCE_CREATED)
    )

    When(s"we associate priciple role '$principalRoleName' with catalog role name '$catalogRoleName'")
    assert(permissions
      .associateRoles(warehouseName, principalRoleName, catalogRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

}