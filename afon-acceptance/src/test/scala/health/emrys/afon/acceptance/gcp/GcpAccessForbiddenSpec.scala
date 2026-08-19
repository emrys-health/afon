package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Forbidden, Gcp}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Gcp
@Forbidden
class GcpAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  def config: PolarisCatalog = AcceptanceTestConfig.instance.getGcpForbidden

  override def cloudProvider: AfonCloud = AfonCloud.GCP_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/gcp/create.sh")
  }

}
