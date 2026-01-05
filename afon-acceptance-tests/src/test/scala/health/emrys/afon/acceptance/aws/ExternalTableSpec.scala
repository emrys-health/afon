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
 * This needs:

 aws iam create-role --role-name GlueIcebergRole --assume-role-policy-document '{
   "Version": "2012-10-17",
   "Statement": [
     {
       "Effect": "Allow",
       "Principal": {
         "AWS": "arn:aws:iam::418295715997:root"
       } ,
       "Action": "sts:AssumeRole"
     }
   ]
 }'

aws iam attach-role-policy --role-name GlueIcebergRole --policy-arn  arn:aws:iam::418295715997:policy/GlueIcebergAccess

# Not sure this should be attached to my user. What about others? TODO
aws iam put-user-policy \
 --user-name philliphenry \
 --policy-name GlueIcebergAccess \
 --policy-document file://iceberg-policy.json


 aws iam put-role-policy \
   --role-name GlueIcebergRole \
   --policy-name IcebergRestCatalogAccess \
   --policy-document '{
     "Version": "2012-10-17",
     "Statement": [
     {
       "Effect": "Allow",
       "Action": [
        "glue:GetCatalog"
       ],
       "Resource": [
        "arn:aws:glue:eu-west-2:418295715997:catalog"
       ]
     }
     ]
   }'

aws glue create-table --database-name ph_glue_iceberg_db --table-input '
   {
     "Name": "DatumAccTest2",
     "TableType": "EXTERNAL_TABLE",
     "StorageDescriptor": {
     "Location": "s3://emrys-afon-bucket-2/AwsAccessSpec251117511401/",
     "Columns": [
       { "Name": "id", "Type": "int" },
       { "Name": "label", "Type": "string" },
       { "Name": "partitionKey", "Type": "bigint" },
       { "Name": "date", "Type": "date" },
       { "Name": "timestamp", "Type": "timestamp" },
       { "Name": "randomInt", "Type": "int" }
     ],
     "SerdeInfo": {
      "SerializationLibrary": "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"
     }
     },
     "Parameters": {
      "iceberg.table.default.namespace": "ph_glue_iceberg_db"

      }
   }'     --open-table-format-input '
   {
     "IcebergInput": {
       "MetadataOperation": "CREATE",
       "Version": "2"
     }
   }'     --region eu-west-2

 # this causes problems when added to the Parameters section:
 # , "metadata_location": "s3://emrys-afon-bucket-2/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json"
 */
class ExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  val FEDERATED_CATALOG_NAME = "federatedCatalogName"
  val REST_CODE_RESOURCE_CREATED = 201

  "An external table" should {
    val properties        = new AfonPropertiesLoader().getProperties
    val externalBucket    = AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties)
    properties.setProperty(AWS_S3_BUCKET_NAME.getPropertyName, externalBucket)
    val config            = new AwsCloudConfigForPolaris(properties)
    val grantCatalogAdmin = new CatalogAdminGrant(this, config, Some(FEDERATED_CATALOG_NAME))
    val catalogRoles      = new CatalogRoles(this, config, Some(FEDERATED_CATALOG_NAME))
    "be visible by our Polaris" in {
      Given(f"a bucket called $externalBucket")

      val json: String = externalCatalogJson(properties, externalBucket, config)
      When(f"Polaris is called with payload:\n$json")

      val response = client.postJsonCall(
        PolarisConfigurer.getCreateCatalogURI,
        json,
        accessToken)
      val responseCode = response.getCode
      Then("the response is HTTP 2XX indicating the external catalog was created")
      assert(responseCode / 100 == 2) // || responseCode == 409) // remove that 409
    }
    "be told about the catalog admin grants" in grantCatalogAdmin()
    "be told about the catalog roles" in catalogRoles()
    "can be queries via Spark" in {
      val sparkConf = sparkWarehouse.sparkConf(4, FEDERATED_CATALOG_NAME)
      val spark: SparkSession = SparkSession
        .builder()
        .config(sparkConf)
        .getOrCreate()
      val ns = "ph_glue_iceberg_db"
      spark.sql(s"CREATE NAMESPACE IF NOT EXISTS $FEDERATED_CATALOG_NAME.$ns")
      val sql = String.format("SELECT * FROM %s.%s.%s", FEDERATED_CATALOG_NAME, ns, "DatumAccTest2")
      spark.sql(sql).show()
    }
  }


  private def externalCatalogJson(properties: Properties, externalBucket: String, config: AwsCloudConfigForPolaris) = {
    val externalCatalogProperties = CatalogProperties
      .builder()
      .setDefaultBaseLocation(f"s3a://$externalBucket") // needs to be different to any other Polaris knows about
      .build()

    val region = AWS_REGION.getValueFrom(properties)
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

    val json = jsonMaker.toJson(new CreateCatalogRequest(catalog))
    json
  }
}
