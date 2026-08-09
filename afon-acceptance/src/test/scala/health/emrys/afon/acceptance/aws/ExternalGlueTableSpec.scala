package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.tags.{Aws, External}
import health.emrys.afon.acceptance.{AcceptanceTestConfig, CatalogAdminGrant, CatalogRoles}
import health.emrys.afon.config.AfonProperties.{AWS_REGION, AWS_S3_EXTERNAL_BUCKET_NAME, GLUE_CATALOG_NAME}
import health.emrys.afon.config.FilePropertyProvider
import health.emrys.afon.polaris.catalogs.GlueCloudConfigForPolaris
import health.emrys.afon.polaris.config.PolarisConfigurer
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

/**
 * You'll need to run `scripts/aws/create_federated_permissions.sh` first if it's not been done already.
 */
@Aws
@External
class ExternalGlueTableSpec extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisAdminClient
  val accessToken = AcceptanceTestConfig.instance.getAdminAccessToken()

  "An external table" should {
    val properties        = new FilePropertyProvider().getProperties
    val glueCatalogName   = GLUE_CATALOG_NAME.getValueFrom(properties)
    val externalBucket    = AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties)
    val config            = new GlueCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(config.warehouseName()))
    val catalogRoles      = new CatalogRoles(this, config, Some(config.warehouseName))
    val tableName         = "DatumAccTest2"
    val sparkNamespace    = "ph_glue_iceberg_db"
    val awsRegion         = AWS_REGION.getValueFrom(properties)

    "be visible to our Polaris" in {
      Given(f"a bucket called $externalBucket and somebody has run the command:\n${ExternalGlueTableSpec.glueCmdLine(
        sparkNamespace, tableName, awsRegion, externalBucket
      )}")

      val json: String = config.catalogConfig()
      val uri: String  = PolarisConfigurer.getCreateCatalogURI
      When(f"Polaris is invoked at URI '$uri' with payload:\n$json")

      val response = client.postJsonCall(
        uri,
        json,
        accessToken)
      val responseCode = response.getCode
      Then("the response is HTTP 2XX indicating the external catalog was created")
      assert(responseCode / 100 == 2)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in catalogRoles()
    "can be queried via Spark" in {
      Given(f"a namespace called $glueCatalogName")
      val sparkConf = sparkWarehouse.sparkConf(4, glueCatalogName)
      val spark: SparkSession = SparkSession
        .builder()
        .config(sparkConf)
        .getOrCreate()

      val nsSQL = s"CREATE NAMESPACE IF NOT EXISTS $glueCatalogName.$sparkNamespace"
      val sql = String.format("SELECT * FROM %s.%s.%s", glueCatalogName, sparkNamespace, tableName)
      When(f"we execute:\n$nsSQL\n$sql")
      spark.sql(nsSQL)

      Then("we can query the table")
      spark.sql(sql).show()
      And(
        f"""
          |this result is the same as if we had run:
          |
          |aws athena start-query-execution \\
          |  --query-string "SELECT * FROM ${glueCatalogName}.${tableName} LIMIT 10;" \\
          |  --query-execution-context Database=${glueCatalogName} \\
          |  --result-configuration OutputLocation=${externalBucket}/AwsAccessSpec251117511401/
          |aws athena get-query-execution --query-execution-id QUERY_ID_FROM_ABOVE
          |aws s3 cp OUTPUT_LOCATION_FROM_ABOVE -
          |""".stripMargin)
    }
  }

}

object ExternalGlueTableSpec {
  def glueCmdLine(dbName: String,
                  tblName: String,
                  region: String,
                  bucket: String): String = String.format(
    """
      |aws glue create-table --database-name %s --table-input '
      |   {
      |     "Name": "%s",
      |     "TableType": "EXTERNAL_TABLE",
      |     "StorageDescriptor": {
      |     "Location": "s3://%s/AwsAccessSpec251117511401/",
      |     "Columns": [
      |       { "Name": "id", "Type": "int" },
      |       { "Name": "label", "Type": "string" },
      |       { "Name": "partitionKey", "Type": "bigint" },
      |       { "Name": "date", "Type": "date" },
      |       { "Name": "timestamp", "Type": "timestamp" },
      |       { "Name": "randomInt", "Type": "int" }
      |     ],
      |     "SerdeInfo": {
      |      "SerializationLibrary": "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"
      |     }
      |     },
      |     "Parameters": {
      |       "iceberg.table.default.namespace": "%s"
      |      }
      |   }'     --open-table-format-input '
      |   {
      |     "IcebergInput": {
      |       "MetadataOperation": "CREATE",
      |       "Version": "2"
      |     }
      |   }'     --region %s
      |""".stripMargin,
    dbName, tblName, bucket, dbName, region
  )

  def main(args: Array[String]): Unit = println(glueCmdLine(
    "ph_glue_iceberg_db",
    "DatumAccTest2",
    "eu-west-2",
    "emrys-afon-bucket-2",
  ))
}
