package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AbstractAccessForbiddenSpec, AcceptanceTestConfig, FileHelper}
import health.emrys.afon.acceptance.tags.{Azure, Forbidden}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE_FORBIDDEN
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

@Azure
@Forbidden
class AzureAccessForbiddenSpec extends AbstractAccessForbiddenSpec with AzureTableCreation {

  def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAzureForbidden

  override def cloudProvider: SupportedCloudsEnum = AZURE_FORBIDDEN

  def readEnvSetup(): String = {
    FileHelper.readFromRoot("scripts/azure/create.sh")
  }

  override protected def createTableAndPopulate(fqn: String): Exception = {
    intercept[org.apache.iceberg.exceptions.RESTException] {
      spark.sql(createDatumTable(s" IF NOT EXISTS $fqn"))
      spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(fqn).append()
    }
  }

}
