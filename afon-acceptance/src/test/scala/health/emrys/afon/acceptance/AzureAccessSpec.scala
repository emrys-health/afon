package health.emrys.afon.acceptance

import SparkHelper.spark
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import uk.co.odinconsultants.documentation_utils.Datum

import java.lang.reflect.Field

class AzureAccessSpec extends AbstractAccessSpec with AzureTableCreation {

  override def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAzureCloudConfigForPolaris

  override def cloudProvider: SupportedCloudsEnum = SupportedCloudsEnum.AZURE

  override protected def createTableAndPopulate(): Unit = {
    spark.sql(createDatumTable(s" IF NOT EXISTS $FULL_NAMESPACE.$tableName"))
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).append()
  }

}
