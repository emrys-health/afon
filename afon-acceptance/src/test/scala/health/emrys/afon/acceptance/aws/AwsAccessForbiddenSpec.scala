package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Aws, Forbidden}
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Aws
@Forbidden
class AwsAccessForbiddenSpec extends AbstractAccessForbiddenSpec {

  private lazy val AWS_FORBIDDEN = AcceptanceTestConfig.instance.afonCatalogs().awsForbidden()

  def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(AWS_FORBIDDEN)

  override def afonCatalog(): Any = AWS_FORBIDDEN

  def envSetupScript(): String = {
    "scripts/aws/create.sh"
  }

}
