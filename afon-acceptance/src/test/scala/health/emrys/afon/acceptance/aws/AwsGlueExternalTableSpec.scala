package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.SparkHelper.sparkWarehouse
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.{Aws, External}
import health.emrys.afon.acceptance.{AcceptanceTestConfig, GrantCatalogAdmin, CreateCatalogRole}
import health.emrys.afon.config.AfonProperty.{AWS_ACCESS_KEY_ID, AWS_REGION, AWS_SECRET_ACCESS_KEY, ENV_NAME}
import health.emrys.afon.polaris.catalogs.GlueAwsPolarisCatalog
import org.apache.spark.sql.SparkSession
import org.scalatest.GivenWhenThen
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.glue.GlueClient
import software.amazon.awssdk.services.glue.model._

import scala.jdk.CollectionConverters._

/**
 * You'll need to run `scripts/aws/create_federated_permissions.sh` first if it's not been done already.
 */
@Aws
@External
class AwsGlueExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val properties = AcceptanceTestConfig.instance.getProperties
  val catalogManager = AcceptanceTestConfig.instance.getCatalogManager
  val afonCatalogs = AcceptanceTestConfig.instance.afonCatalogs()

  // catalogs-aws.properties documents "aws_glue" as omittable when AWS_GLUE_ROLE_ARN isn't
  // available, so its absence is a supported config, not a misconfiguration - check for it with
  // a plain list scan (rather than the throwing AfonCatalogs.get) so a spec-registration-time
  // lookup failure doesn't abort the whole suite (ScalaTest can't recover once that happens,
  // unlike a failure/cancellation inside a running test).
  private val GlueCatalogId = "aws_glue"
  private val glueConfigured = afonCatalogs.all().asScala.exists(_.id().equalsIgnoreCase(GlueCatalogId))

  if (glueConfigured) {

    // "glue" is a one-off type registered explicitly in PolarisCatalogManager (see
    // GlueAwsPolarisCatalog), rather than one of the generic internal/external shapes
    // AcceptanceTestConfig pre-registers - so it's built directly here from its AfonCatalog.
    val afonCatalog       = afonCatalogs.get(GlueCatalogId)
    val glueCatalog       = new GlueAwsPolarisCatalog(afonCatalog, properties)
    val glueCatalogName   = glueCatalog.catalogName()
    // GlueAwsPolarisCatalog reuses aws_external's configured bucket (see catalogs-aws.properties).
    val externalBucket    = properties.getCatalogProperty("AWS_BUCKET_NAME", "aws_external")
    val grantCatalogAdmin = new GrantCatalogAdmin(this, glueCatalog, Some(glueCatalog.catalogName()))
    val createCatalogRole = new CreateCatalogRole(this, glueCatalog, Some(glueCatalog.catalogName))
    // Suffixed by ENV_NAME: the underlying Glue table is a real, shared, account-wide resource
    // (not scoped per Afon environment), so each environment needs its own distinctly-named table
    // to avoid colliding on the same database.table with a different expected S3 location.
    val tableName         = s"DatumAccTest2_${properties.getValue(ENV_NAME)}"
    val sparkNamespace    = "ph_glue_iceberg_db"
    val awsRegion         = properties.getValue(AWS_REGION)
    val awsCredentials    = AwsGlueExternalTableSpec.credentialsProvider(
      properties.getValue(AWS_ACCESS_KEY_ID, ""), properties.getValue(AWS_SECRET_ACCESS_KEY, ""))

    "have its underlying Iceberg table created if absent" in {
      Given(f"a bucket called $externalBucket")
      When(f"we ensure the table $sparkNamespace.$tableName exists")
      AwsGlueExternalTableSpec.ensureTableExists(awsRegion, awsCredentials, sparkNamespace, tableName, externalBucket)
      Then(f"the table exists, created now or already there from a previous run")
    }
    "be visible to our Polaris" in {
      Given(f"the table $sparkNamespace.$tableName exists")
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
          |  --result-configuration OutputLocation=${externalBucket}/${AwsGlueExternalTableSpec.TableLocationSuffix}/
          |aws athena get-query-execution --query-execution-id QUERY_ID_FROM_ABOVE
          |aws s3 cp OUTPUT_LOCATION_FROM_ABOVE -
          |""".stripMargin)
    }

  } else {

    "An external table" should {
      s"be skipped: '$GlueCatalogId' is not configured" in {
        cancel(s"'$GlueCatalogId' is not in AWS_CATALOG_IDS - add it (with AWS_GLUE_ROLE_ARN) to " +
          "catalogs-aws.properties/afon-aws.properties to run this spec.")
      }
    }

  }

}

object AwsGlueExternalTableSpec {

  private val TableLocationSuffix = "AwsAccessSpec251117511401"

  private val Columns = List(
    "id" -> "int",
    "label" -> "string",
    "partitionKey" -> "bigint",
    "date" -> "date",
    "timestamp" -> "timestamp",
    "randomInt" -> "int",
  )

  /**
   * The AWS keys of the afon config (secret-aws.properties), as used by Polaris, when both are set - eg in CI,
   * where there is no AWS profile or env to fall back on.  Else the default chain, eg a local AWS profile.
   */
  def credentialsProvider(accessKeyId: String, secretAccessKey: String): AwsCredentialsProvider =
    if (accessKeyId.isBlank || secretAccessKey.isBlank) DefaultCredentialsProvider.builder().build()
    else StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))

  /**
   * Creates the Iceberg-backed Glue table this spec depends on, if it doesn't already exist -
   * previously a manual, easy-to-forget "somebody has run the command" prerequisite; now run by
   * the spec itself so there's no separate setup step to get wrong or skip.
   */
  def ensureTableExists(region: String, credentials: AwsCredentialsProvider,
                        dbName: String, tableName: String, bucket: String): Unit = {
    val glue = GlueClient.builder().region(Region.of(region)).credentialsProvider(credentials).build()
    try {
      val exists =
        try {
          glue.getTable(GetTableRequest.builder().databaseName(dbName).name(tableName).build())
          true
        } catch {
          case _: EntityNotFoundException => false
        }
      if (!exists) {
        val columns = Columns.map { case (name, colType) =>
          Column.builder().name(name).`type`(colType).build()
        }
        val storageDescriptor = StorageDescriptor.builder()
          .location(s"s3://$bucket/$TableLocationSuffix/")
          .columns(columns.asJava)
          .serdeInfo(SerDeInfo.builder()
            .serializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")
            .build())
          .build()
        val tableInput = TableInput.builder()
          .name(tableName)
          .tableType("EXTERNAL_TABLE")
          .storageDescriptor(storageDescriptor)
          .parameters(Map("iceberg.table.default.namespace" -> dbName).asJava)
          .build()
        val openTableFormatInput = OpenTableFormatInput.builder()
          .icebergInput(IcebergInput.builder()
            .metadataOperation(MetadataOperation.CREATE)
            .version("2")
            .build())
          .build()
        glue.createTable(CreateTableRequest.builder()
          .databaseName(dbName)
          .tableInput(tableInput)
          .openTableFormatInput(openTableFormatInput)
          .build())
      }
    } finally {
      glue.close()
    }
  }
}
