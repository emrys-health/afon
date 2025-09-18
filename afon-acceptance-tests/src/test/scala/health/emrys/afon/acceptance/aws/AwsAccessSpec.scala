package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.AcceptanceTestConfig.instance
import health.emrys.afon.spark.SparkWarehouseConfig
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.{SimpleFixture, SpecPretifier}

import java.nio.file.Files

class AwsAccessSpec extends SpecPretifier with GivenWhenThen {

  s"A table" should {
    s"create no new files for " in new SimpleFixture {
      def dataDir(tableName: String) = ???
      val sparkWarehouse = new SparkWarehouseConfig();
      val namespace = "acceptancetests"
      val tableName = sparkWarehouse.getSparkCatalog + "." + namespace + ".AwsAccessSpec"
      val sc: SparkContext       = SparkContext.getOrCreate(sparkWarehouse.sparkConf(
        instance.getAccessToken,
        instance.getClientId,
        instance.getClientSecret,
        instance.getAwsCloudConfigForPolaris.warehouseName,
        namespace
      ))
      val spark: SparkSession    = SparkSession
        .builder()
        .getOrCreate()
      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS ${sparkWarehouse.getSparkCatalog}.$namespace")
      spark.createDataFrame(data).writeTo(tableName).create()
    }
  }
}
