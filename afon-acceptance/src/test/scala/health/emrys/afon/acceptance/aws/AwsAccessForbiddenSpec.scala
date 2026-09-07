package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Aws, Forbidden}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.config.AfonCloud.AWS_FORBIDDEN
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Aws
@Forbidden
class AwsAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(AWS_FORBIDDEN)

  override def cloudProvider: AfonCloud = AWS_FORBIDDEN

  def envSetupScript(): String = {
    "scripts/aws/create.sh"
  }

}
