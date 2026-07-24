package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Azure}
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris

@Azure
@Access
class AzureAccessSpec extends AbstractAccessSpec with AzureTableCreation {

  override def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAzureCloudConfigForPolaris

  override def cloudProvider: SupportedCloudsEnum = SupportedCloudsEnum.AZURE

  override protected def createTableAndPopulate(): Unit = {
    spark.sql(createDatumTable(s" IF NOT EXISTS $FULL_NAMESPACE.$tableName"))
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).append()
  }

}
