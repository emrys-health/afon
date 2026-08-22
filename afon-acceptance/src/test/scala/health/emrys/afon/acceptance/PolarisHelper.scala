package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AbstractAccessSpec.REST_CODE_RESOURCE_CREATED
import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.acceptance.PolarisHelper.{principalName, principalRoleName}
import health.emrys.afon.config.AfonProperty
import health.emrys.afon.polaris.catalogs.{JsonSerialiser, PolarisSerialiser}
import health.emrys.afon.polaris.config.{PermissionsConfigurer, PolarisConfigurer}
import org.apache.polaris.core.admin.model.PrincipalWithCredentials

object PolarisHelper {
  val principalName     = instance.getProperties().getValue(AfonProperty.USER_POLARIS_ID)
  val principalRoleName = "myprincipalrole"
  val principal         = (new PolarisHelper).createPrincipal()
}

class PolarisHelper {
  val accessToken = instance.getAdminAccessToken()
  val client      = instance.getPolarisAdminClient()
  val permissions = new PermissionsConfigurer(client)
  val serialiser  = new PolarisSerialiser(new JsonSerialiser())

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