package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.SparkHelper.spark
import health.emrys.afon.acceptance.{AbstractAccessSpec, AcceptanceTestConfig}
import health.emrys.afon.acceptance.tags.{Access, Azure}
import health.emrys.afon.polaris.catalogs.PolarisCatalog

@Azure
@Access
class AzureAccessSpec extends AbstractAccessSpec with AzureTableCreation {

  private lazy val AZURE = AcceptanceTestConfig.instance.afonCatalogs().azure()

  override def catalog: PolarisCatalog = AcceptanceTestConfig.instance.getCatalog(AZURE)

  override def afonCatalog(): Any = AZURE

  override protected def createTableAndPopulate(): Unit = {
    spark.sql(createDatumTable(s" IF NOT EXISTS $FULL_NAMESPACE.$tableName"))
    spark.createDataFrame(syntheticData(NUM_ROWS)).writeTo(tableNameFQN).append()
  }

}
