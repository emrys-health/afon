package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Aws}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Aws
@Access
class AwsAccessSpec extends AbstractAccessSpec {

  override def config: PolarisCatalog = AcceptanceTestConfig.instance.getAwsCloudConfigForPolaris

  override def cloudProvider: AfonCloud = AfonCloud.AWS
}
