package health.emrys.afon.acceptance

import health.emrys.afon.acceptance.AcceptanceTestConfig.instance
import health.emrys.afon.acceptance.PolarisHelper.{principalName, principalRoleName}
import health.emrys.afon.config.AfonProperty
import health.emrys.afon.polaris.config.PolarisUsersManager
import org.apache.polaris.core.admin.model.PrincipalWithCredentials

object PolarisHelper {
  val principalName     = instance.getProperties().getValue(AfonProperty.USER_POLARIS_ID)
  val principalRoleName = "myprincipalrole"
  val principal         = (new PolarisHelper).createPrincipal()
}

class PolarisHelper {
  def createPrincipal(): PrincipalWithCredentials = {
    val usersManager = new PolarisUsersManager(instance.getProperties)
    val principal    = usersManager.createPrincipal(principalName)
    usersManager.createPrincipalRole(principalRoleName)
    usersManager.associatePrincipalRole(principalName, principalRoleName)
    principal
  }
}