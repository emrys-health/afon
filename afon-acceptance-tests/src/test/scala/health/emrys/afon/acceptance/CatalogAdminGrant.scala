package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.PolarisHelper.principalRoleName
import health.emrys.afon.polaris.catalogs.{CloudConfigForPolaris, JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.PermissionsConfigurer
import health.emrys.afon.polaris.comms.PolarisConfigurer._
import org.apache.polaris.core.admin.model.CatalogPrivilege._
import org.scalatest.GivenWhenThen

abstract class PolarisFixture extends Function0[Any] {

  val REST_CODE_RESOURCE_CREATED = 201
  val REST_CODE_CONFLICT          = 409

  val client = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()
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
                        warehouseNameOpt: Option[String] = None,
                        noisy: Boolean = true) extends PolarisFixture {

  import test._

  override def apply(): Any = {
    if (noisy) Given(s"a Polaris endpoint at ${client.getEndpoint}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())
    val uri           = getCatalogAdminGrantsURI(warehouseName)
    val json          = dtos.catalogAdminGrants
    if (noisy) When(s"we call '$uri' with payload:\n$json")
    val response      = client.putJsonCall(uri, json, accessToken)
    if (noisy) Then(s"Polaris indicates the HTTP request lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
    assert(response.getCode == REST_CODE_RESOURCE_CREATED)
  }
}

class CatalogRoles(test: GivenWhenThen,
                   config: CloudConfigForPolaris,
                   warehouseNameOpt: Option[String] = None,
                   noisy: Boolean = true) extends PolarisFixture {

  import test._

  val catalogRoleName = "mycatalogrole"
  val permissions = new PermissionsConfigurer(client)

  def apply = {
    if (noisy) Given(s"a Polaris endpoint at ${client.getEndpoint}")
    if (noisy) And(s"a principal that's already been created:\n${PolarisHelper.principal.getPrincipal}")
    val warehouseName = warehouseNameOpt.getOrElse(config.warehouseName())

    if (noisy) When(s"we create a catalog role name called '$catalogRoleName'")
    val createCode = permissions
      .createCatalogRole(warehouseName, catalogRoleName)
      .getCode
    assert(createCode == REST_CODE_RESOURCE_CREATED || createCode == REST_CODE_CONFLICT)

    val privileges = Set(TABLE_READ_DATA, TABLE_WRITE_DATA, NAMESPACE_CREATE, TABLE_CREATE, NAMESPACE_FULL_METADATA)
    if (noisy) When(s"we add to the catalog role '$catalogRoleName' permissions:\n${privileges.mkString("\n")}")
    privileges.foreach(privilege =>
      assert(permissions
        .createCatalogRoleGrant(warehouseName, privilege, catalogRoleName)
        .getCode == REST_CODE_RESOURCE_CREATED)
    )

    if (noisy) When(s"we associate principal role '$principalRoleName' with catalog role name '$catalogRoleName'")
    assert(permissions
      .associateRoles(warehouseName, principalRoleName, catalogRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    if (noisy) Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

}