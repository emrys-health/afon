package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.acceptance.PolarisHelper.{principalName, principalRoleName}
import health.emrys.afon.config.AfonProperty
import health.emrys.afon.polaris.config.PolarisUserManager
import org.apache.polaris.core.admin.model.PrincipalWithCredentials

object PolarisHelper {
  val principalName     = instance.getProperties().getValue(AfonProperty.USER_ID)
  val principalRoleName = "myprincipalrole"
  val principal         = (new PolarisHelper).createPrincipal()
}

class PolarisHelper {
  def createPrincipal(): PrincipalWithCredentials = {
    val userManager = new PolarisUserManager(instance.getProperties)
    val principal   = userManager.createPrincipal(principalName)
    userManager.createPrincipalRole(principalRoleName)
    userManager.associatePrincipalRole(principalName, principalRoleName)
    principal
  }
}