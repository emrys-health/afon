package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.spark
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris
import uk.co.odinconsultants.documentation_utils.Datum

import java.lang.reflect.Field

class AzureAccessSpec extends AbstractAccessSpec {

  override def config: CloudConfigForPolaris = AcceptanceTestConfig.instance.getAzureCloudConfigForPolaris

  override def cloudProvider: SupportedCloudsEnum = SupportedCloudsEnum.AZURE

  override protected def createTableAndPopulate(): Unit = {
    spark.sql(createDatumTable(s" IF NOT EXISTS $FULL_NAMESPACE.$tableName"))
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).append()
  }

  def createDatumTable(tableName: String): String = {
    val fields: String = classOf[Datum].getDeclaredFields
      .map { field: Field =>
        s"${field.getName} ${field.getType.getSimpleName}"
      }
      .mkString(",\n")
    s"""CREATE TABLE $tableName ($fields) USING iceberg""".stripMargin
  }
}
