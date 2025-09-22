package health.emrys.afon.acceptance.aws

import health.emrys.afon.config.AfonProperties
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

class GcpAccessSpec extends AbstractAccessSpec {

  override def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getGcpCloudConfigForPolaris

  override def cloudProvider: AfonProperties.SupportedCloudsEnum = AfonProperties.SupportedCloudsEnum.GCP
}
