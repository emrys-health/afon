package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Azure, Forbidden}
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Azure
@Forbidden
class AzureAccessForbiddenSpec extends AbstractAccessForbiddenSpec with AzureTableCreation {

  def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getAzureForbidden

  override def cloudProvider: AfonCloud = AfonCloud.AZURE_FORBIDDEN

  def envSetupScript(): String = {
    "scripts/azure/create.sh"
  }

  override protected def createTableAndPopulate(fqn: String): Exception = {
    intercept[org.apache.iceberg.exceptions.RESTException] {
      spark.sql(createDatumTable(s" IF NOT EXISTS $fqn"))
      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(fqn).append()
    }
  }

}
