package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AbstractAccessSpec.REST_CODE_RESOURCE_CREATED
import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.acceptance.PolarisHelper.{principalRoleName, principleName}
import health.emrys.afon.polaris.catalogs.{JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.{PermissionsConfigurer, PolarisConfigurer}
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getPrincipleToRolesURI, principalRolesURI}
import org.apache.polaris.core.admin.model.PrincipalWithCredentials

object PolarisHelper {
  val principleName     = "myprincipleName"
  val principalRoleName = "myprinciplerole"
  val principle         = (new PolarisHelper).createPrinciple()
}

class PolarisHelper {
  val dtos        = new PolarisDTOs(new JsonMaker())
  val accessToken = instance.getAccessToken()
  val client      = instance.getPolarisClient()
  val permissions = new PermissionsConfigurer(client)

  def createPrinciple(): PrincipalWithCredentials = {
    val configurer = new PolarisConfigurer(client)
    val principle  = configurer.configureUser(accessToken, principleName)

    assert(permissions
      .createPrincipalRoles(principalRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)
    assert(permissions
      .associatePrincipleRoles(principleName, principalRoleName)
      .getCode == REST_CODE_RESOURCE_CREATED)

    principle
  }
}