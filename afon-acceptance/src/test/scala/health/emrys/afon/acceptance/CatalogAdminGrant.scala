package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.PolarisHelper.principalRoleName
import health.emrys.afon.polaris.catalogs.{PolarisCatalog, JsonSerialiser, PolarisSerialiser}
import health.emrys.afon.polaris.config.CatalogConfigurer
import health.emrys.afon.polaris.config.CatalogConfigurer._
import org.apache.polaris.core.admin.model.CatalogPrivilege._
import org.scalatest.GivenWhenThen

abstract class PolarisFixture extends Function0[Any] {

  val REST_CODE_RESOURCE_CREATED = 201

  val client = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()
  val serialiser = new PolarisSerialiser(new JsonSerialiser())

}

/**
 * Without this, you'll see something like:
 * org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Principal 'root'
 * with activated PrincipalRoles '[service_admin]' and activated grants via
 * '[service_admin, catalog_admin]' is not authorized for op
 * CREATE_TABLE_STAGED_WITH_WRITE_DELEGATION
 */
class CatalogAdminGrant(test: GivenWhenThen,
                        config: PolarisCatalog,
                        warehouseNameOpt: Option[String] = None,
                        noisy: Boolean = true) extends PolarisFixture {

  import test._

  override def apply(): Any = {
    if (noisy) Given(s"a Polaris endpoint at ${client.getEndpoint}")
    val warehouseName = warehouseNameOpt.getOrElse(config.catalogName())
    val uri           = catalogRolesAdminGrantsURI(warehouseName)
    val json          = serialiser.catalogAdminGrants
    if (noisy) When(s"we call '$uri' with payload:\n$json")
    val response      = client.put(uri, json, accessToken)
    if (noisy) Then(s"Polaris indicates the HTTP request lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
    assert(response.getCode == REST_CODE_RESOURCE_CREATED)
  }
}

class CatalogRoles(test: GivenWhenThen,
                   config: PolarisCatalog,
                   catalogNameOpt: Option[String] = None,
                   noisy: Boolean = true) extends PolarisFixture {

  import test._

  val catalogRoleName = "mycatalogrole"
  val configurer = new CatalogConfigurer(client)

  def apply = {
    if (noisy) Given(s"a Polaris endpoint at ${client.getEndpoint}")
    if (noisy) And(s"a principal that's already been created:\n${PolarisHelper.principal.getPrincipal}")
    val catalogName = catalogNameOpt.getOrElse(config.catalogName())

    if (noisy) When(s"we create a catalog role name called '$catalogRoleName'")
    assert(configurer
      .createCatalogRole(catalogName, catalogRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    val privileges = Set(TABLE_READ_DATA, TABLE_WRITE_DATA, NAMESPACE_CREATE, TABLE_CREATE, NAMESPACE_FULL_METADATA)
    if (noisy) When(s"we add to the catalog role '$catalogRoleName' configurer:\n${privileges.mkString("\n")}")
    privileges.foreach(privilege =>
      assert(configurer
        .createCatalogRoleGrant(catalogName, catalogRoleName, privilege)
        .getCode == REST_CODE_RESOURCE_CREATED)
    )

    if (noisy) When(s"we associate principal role '$principalRoleName' with catalog role name '$catalogRoleName'")
    assert(configurer
      .associatePrincipalRole(catalogName, principalRoleName, catalogRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    if (noisy) Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

}