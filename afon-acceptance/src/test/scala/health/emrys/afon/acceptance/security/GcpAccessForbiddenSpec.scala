package health.emrys.afon.acceptance.security

import health.emrys.afon.acceptance.{AcceptanceTestConfig, FileHelper}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP_FORBIDDEN
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

class GcpAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getGcpForbidden

  override def cloudProvider: SupportedCloudsEnum = GCP_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/gcp/create.sh")
  }

}
