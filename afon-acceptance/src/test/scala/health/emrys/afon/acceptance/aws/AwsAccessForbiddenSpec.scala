package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Aws, Forbidden}
import health.emrys.afon.config.AfonProperties.AfonCloud
import AfonCloud.AWS_FORBIDDEN
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

@Aws
@Forbidden
class AwsAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAwsForbidden

  override def cloudProvider: AfonCloud = AWS_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/aws/create.sh")
  }

}
