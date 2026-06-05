package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AbstractAccessSpec.REST_CODE_RESOURCE_CREATED
import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.polaris.catalogs.{JsonMaker, PolarisDTOs}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getPrincipleToRolesURI, principalRolesURI}

object PolarisHelper {
  val principleName = "myprincipleName"
  val principalRoleName = "myprinciplerole"
  val dtos = new PolarisDTOs(new JsonMaker())
  val accessToken = instance.getAccessToken()
  val client = instance.getPolarisClient()

  private def createPrincipalRoles() = {
    val uri = principalRolesURI
    val json = dtos.principalRoleCreation(principalRoleName)
    client.postJsonCall(uri, json, accessToken)
  }
  def associatePrincipleRoles() = {
    val uri = getPrincipleToRolesURI(principleName)
    val json = dtos.principalRoleCreation(principalRoleName)
    client.putJsonCall(uri, json, accessToken)
  }

  def apply() = {
    val configurer = new PolarisConfigurer(client)
    val principle = configurer.configureUser(accessToken, principleName)

    assert(createPrincipalRoles().getCode == REST_CODE_RESOURCE_CREATED)
    assert(associatePrincipleRoles().getCode == REST_CODE_RESOURCE_CREATED)
  }

}
