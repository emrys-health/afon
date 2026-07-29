package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Aws}
import health.emrys.afon.config.AfonProperties
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

@Aws
@Access
class AwsAccessSpec extends AbstractAccessSpec {

  override def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAwsCloudConfigForPolaris

  override def cloudProvider: AfonProperties.SupportedCloudsEnum = AfonProperties.SupportedCloudsEnum.AWS
}
