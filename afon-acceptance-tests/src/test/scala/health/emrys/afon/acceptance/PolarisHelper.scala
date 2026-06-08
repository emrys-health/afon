package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AbstractAccessSpec.REST_CODE_RESOURCE_CREATED
import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.acceptance.PolarisHelper.{principalRoleName, principalName}
import health.emrys.afon.polaris.catalogs.{JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.{PermissionsConfigurer, PolarisConfigurer}
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getPrincipalToRolesURI, principalRolesURI}
import org.apache.polaris.core.admin.model.PrincipalWithCredentials

object PolarisHelper {
  val principalName     = "myprincipalName"
  val principalRoleName = "myprincipalrole"
  val principal         = (new PolarisHelper).createPrincipal()
}

class PolarisHelper {
  val dtos        = new PolarisDTOs(new JsonMaker())
  val accessToken = instance.getAccessToken()
  val client      = instance.getPolarisClient()
  val permissions = new PermissionsConfigurer(client)

  def createPrincipal(): PrincipalWithCredentials = {
    val configurer = new PolarisConfigurer(client)
    val principal  = configurer.configureUser(accessToken, principalName)

    assert(permissions
      .createPrincipalRoles(principalRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)
    assert(permissions
      .associatePrincipalRoles(principalName, principalRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    principal
  }
}