package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.{Aws, External}
import health.emrys.afon.acceptance.{AcceptanceTestConfig, GrantCatalogAdmin, CreateCatalogRole}
import health.emrys.afon.config.AfonProperty.{AWS_REGION, AWS_GLUE_CATALOG_NAME, ENV_NAME}
import health.emrys.afon.config.FilePropertyProvider
import health.emrys.afon.polaris.catalogs.GlueAwsPolarisCatalog
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen

/**
 * You'll need to run `scripts/aws/create_federated_permissions.sh` first if it's not been done already.
 */
@Aws
@External
class AwsGlueExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val properties = AcceptanceTestConfig.instance.getProperties
  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager

  "An external table" should {
    val glueCatalogName   = properties.getValue(AWS_GLUE_CATALOG_NAME)
    // GlueAwsPolarisCatalog reuses aws_external's configured bucket (see catalogs-aws.properties).
    val externalBucket    = properties.getCatalogProperty("AWS_BUCKET_NAME", "aws_external")
    val glueCatalog       = new GlueAwsPolarisCatalog(properties)
    val grantCatalogAdmin = new GrantCatalogAdmin(this, glueCatalog, Some(glueCatalog.catalogName()))
    val createCatalogRole = new CreateCatalogRole(this, glueCatalog, Some(glueCatalog.catalogName))
    // Suffixed by ENV_NAME: the underlying Glue table is a real, shared, account-wide resource
    // (not scoped per Afon environment), so each environment needs its own distinctly-named table
    // to avoid colliding on the same database.table with a different expected S3 location.
    val tableName         = s"DatumAccTest2_${properties.getValue(ENV_NAME)}"
    val sparkNamespace    = "ph_glue_iceberg_db"
    val awsRegion         = properties.getValue(AWS_REGION)

    "be visible to our Polaris" in {
      Given(f"a bucket called $externalBucket and somebody has run the command:\n${AwsGlueExternalTableSpec.glueCmdLine(
        sparkNamespace, tableName, awsRegion, externalBucket
      )}")

      When(f"Polaris is configured with the Glue catalog '${glueCatalog.catalogName()}'")
      val response = catalogManager.createCatalogIfAbsent(glueCatalog)
      val responseCode = response.getCode
      Then("the response is HTTP 2XX indicating the external catalog was created")
      assert(responseCode / 100 == 2)
    }
    "be given catalog admin grants" in grantCatalogAdmin()
    "be given the catalog roles" in createCatalogRole()
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

object AwsGlueExternalTableSpec {
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

  def main(args: Array[String]): Unit = {
    val properties = new FilePropertyProvider()
    println(glueCmdLine(
      "ph_glue_iceberg_db",
      s"DatumAccTest2_${properties.getValue(ENV_NAME)}",
      properties.getValue(AWS_REGION),
      "emrys2-afon-bucket-2",
    ))
  }
}
