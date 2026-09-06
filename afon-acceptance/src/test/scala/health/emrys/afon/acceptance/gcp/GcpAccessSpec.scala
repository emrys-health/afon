package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Gcp}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Gcp
@Access
class GcpAccessSpec extends AbstractAccessSpec {

  override def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getGcpCatalog

  override def cloudProvider: AfonCloud = AfonCloud.GCP
}
