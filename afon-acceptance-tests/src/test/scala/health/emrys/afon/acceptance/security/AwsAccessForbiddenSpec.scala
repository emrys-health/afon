package health.emrys.afon.acceptance.security

import health.emrys.afon.acceptance.{AcceptanceTestConfig, FileHelper}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS_FORBIDDEN
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

class AwsAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAwsForbidden

  override def cloudProvider: SupportedCloudsEnum = AWS_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/aws/create.sh")
  }

}
