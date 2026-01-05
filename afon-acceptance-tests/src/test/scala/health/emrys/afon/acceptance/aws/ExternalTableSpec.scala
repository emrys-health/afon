package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.aws.SparkHelper.sparkWarehouse
import health.emrys.afon.config.AfonProperties.{AWS_REGION, AWS_S3_BUCKET_NAME, AWS_S3_EXTERNAL_BUCKET_NAME}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.{AwsCloudConfigForPolaris, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import health.emrys.afon.polaris.comms.PolarisConfigurer.{getCatalogAdminGrantsURI, getCatalogRolesURI}
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model._
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util.Properties

/**
 * You'll need to run `scripts/aws/create_federated_permissions.sh` for your user (ph1ll1phenry is hardcoded) first.
 */
class ExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  val FEDERATED_CATALOG_NAME = "federatedCatalogName"

  "An external table" should {
    val properties        = new AfonPropertiesLoader().getProperties
    val externalBucket    = AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties)
    properties.setProperty(AWS_S3_BUCKET_NAME.getPropertyName, externalBucket)
    val config            = new AwsCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    val tableName         = "DatumAccTest2"
    val sparkNamespace    = "ph_glue_iceberg_db"
    val awsRegion         = AWS_REGION.getValueFrom(properties)

    "be visible to our Polaris" in {
      Given(f"a bucket called $externalBucket and somebody has run the command:\n${ExternalTableSpec.glueCmdLine(
        sparkNamespace, tableName, awsRegion, externalBucket
      )}")

      val json: String = externalCatalogJson(awsRegion, externalBucket, config)
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
      val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
      val spark: SparkSession = SparkSession
        .builder()
        .config(sparkConf)
        .getOrCreate()

      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$sparkNamespace")

      val sql = String.format("SELECT * FROM %s.%s.%s", FEDERATED_CATALOG_NAME, sparkNamespace, tableName)
      spark.sql(sql).show()
    }
  }


  private def externalCatalogJson(region: String, externalBucket: String, config: AwsCloudConfigForPolaris): String = {
    val externalCatalogProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation(f"s3a://$externalBucket") // needs to be different to any other Polaris knows about
      .build()


    val uri: String = String.format("https://glue.%s.amazonaws.com/iceberg", region)
    val authenticationParameters =
      new SigV4AuthenticationParameters(
        "arn:aws:iam::418295715997:role/GlueIcebergRole",
        null,
        null,
        region,
        "glue",
        AuthenticationTypeEnum.SIGV4
      )

    val externalStorageConfig: AwsStorageConfigInfo = config.getStorageConfigInfo()

    val jsonMaker = new JsonMaker()

    val catalog =
      new ExternalCatalog(
        new ConnectionConfigInfo(ConnectionTypeEnum.ICEBERG_REST, uri, authenticationParameters, null),
        Catalog.TypeEnum.EXTERNAL,
        FEDERATED_CATALOG_NAME,
        externalCatalogProperties,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        null,
        externalStorageConfig)

    jsonMaker.toJson(new CreateCatalogRequest(catalog))
  }
}

object ExternalTableSpec {
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
