package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Aws, Forbidden}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Aws
@Forbidden
class AwsAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def config: PolarisCatalog = AcceptanceTestConfig.instance.getAwsForbidden

  override def cloudProvider: AfonCloud = AfonCloud.AWS_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/aws/create.sh")
  }

}
