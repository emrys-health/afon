package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.PolarisHelper.principalRoleName
import health.emrys.afon.polaris.catalogs.PolarisCatalog
import health.emrys.afon.polaris.config.PolarisUserManager
import org.scalatest.GivenWhenThen

abstract class PolarisFixture extends Function0[Any] {

  val REST_CODE_RESOURCE_CREATED = 201

  val properties = AcceptanceTestConfig.instance.getProperties

}

/**
 * Without this, you'll see something like:
 * org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Principal 'root'
 * with activated PrincipalRoles '[service_admin]' and activated grants via
 * '[service_admin, catalog_admin]' is not authorized for op
 * CREATE_TABLE_STAGED_WITH_WRITE_DELEGATION
 */
class GrantCatalogAdmin(test: GivenWhenThen,
                        config: PolarisCatalog,
                        catalogNameOpt: Option[String] = None,
                        noisy: Boolean = true) extends PolarisFixture {

  import test._

  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

  override def apply(): Any = {
    val catalogName = catalogNameOpt.getOrElse(config.catalogName())
    if (noisy) Given(s"a catalog called '$catalogName' already exists in Polaris")
    if (noisy) When(s"we grant catalog admin privileges for '$catalogName'")
    val response = catalogManager.grantCatalogAdmin(config, catalogName)
    if (noisy) Then(s"Polaris indicates the HTTP request lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
    assert(response.getCode == REST_CODE_RESOURCE_CREATED)
  }
}

class CreateCatalogRole(test: GivenWhenThen,
                        config: PolarisCatalog,
                        catalogNameOpt: Option[String] = None,
                        noisy: Boolean = true) extends PolarisFixture {

  import test._

  val catalogRoleName = "mycatalogrole"
  val privileges = PolarisUserManager.DEFAULT_CATALOG_PRIVILEGES
  val userManager = new PolarisUserManager(properties, catalogRoleName, privileges)

  def apply = {
    val catalogName = catalogNameOpt.getOrElse(config.catalogName())
    if (noisy) Given(s"a principal that's already been created:\n${PolarisHelper.principal.getPrincipal}")

    if (noisy) When(s"we create a catalog role name called '$catalogRoleName' with privileges:\n${privileges}")
    val failures = userManager.createCatalogRoleIfAbsent(catalogName)
    assert(failures.isEmpty)
    assert(catalogRoleName.equals(userManager.getCatalogRoleName))
    assert(privileges.equals(userManager.getPrivileges))

    if (noisy) When(s"we associate principal role '$principalRoleName' with catalog role name '$catalogRoleName'")
    userManager.associatePrincipalRole(catalogName, principalRoleName, catalogRoleName)

    if (noisy) Then(s"Polaris indicates the HTTP requests lead to a creation of a resource (HTTP code $REST_CODE_RESOURCE_CREATED)")
  }

}
