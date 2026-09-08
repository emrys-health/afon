package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Gcp}
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Gcp
@Access
class GcpAccessSpec extends AbstractAccessSpec {

  private lazy val GCP = AcceptanceTestConfig.instance.afonCatalogs().gcp()

  override def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(GCP)

  override def afonCatalog(): Any = GCP
}
