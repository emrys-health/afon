package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.fixtures.Datum
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import health.emrys.afon.config.AfonProperty.GOOGLE_APPLICATION_CREDENTIALS
import health.emrys.afon.config.ConfigUtils

import java.nio.file.{Files, Paths}
import java.sql.{Date, Timestamp}
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter
import java.util.Properties
import scala.util.Using

/**
 * Takes its credentials from GOOGLE_APPLICATION_CREDENTIALS in
 * $AFON_HOME/config/secret-gcp.properties, as the specs do.
 */
object GcpRest {

  def main(args: Array[String]): Unit = {
    // Only the GCP secrets are read: this entry point needs nothing from the other clouds.
    // GoogleAuthManager takes the credentials as the property value itself, not as a path to them.
    val gcpSecrets = Paths.get(ConfigUtils.configDir, "secret-gcp.properties")
    val properties = new Properties()
    Using.resource(Files.newInputStream(gcpSecrets))(in => properties.load(in))
    System.setProperty(
      GOOGLE_APPLICATION_CREDENTIALS.getName,
      GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties))
    println(s"Using GOOGLE_APPLICATION_CREDENTIALS from $gcpSecrets")

    val catalog_name = "CATALOG_NAME"
    val spark = SparkSession.builder.config(new SparkConf().setMaster("local[2]").setAppName("Tests")
        .set(f"spark.sql.catalog.$catalog_name", "org.apache.iceberg.spark.SparkCatalog")
        .set(f"spark.sql.catalog.$catalog_name.header.x-goog-user-project", "fjords-450014")
        .set(f"spark.sql.catalog.$catalog_name.header.X-Iceberg-Access-Delegation","vended-credentials")
        .set(f"spark.sql.catalog.$catalog_name.io-impl", "org.apache.iceberg.gcp.gcs.GCSFileIO")
        .set(f"spark.sql.catalog.$catalog_name.rest.auth.type", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
        .set(f"spark.sql.catalog.$catalog_name.rest-metrics-reporting-enabled", "false")
        .set(f"spark.sql.catalog.$catalog_name.type", "rest")
        .set(f"spark.sql.catalog.$catalog_name.uri", "https://biglake.googleapis.com/iceberg/v1/restcatalog")
        .set(f"spark.sql.catalog.$catalog_name.warehouse", "gs://emrys_afon_core_2")
        .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .set("spark.sql.defaultCatalog", catalog_name))
      .getOrCreate()

      val namespace = "ph_namespace"
      spark.sql("SHOW NAMESPACES IN emrys_afon_core_2").show()
      spark.sql(f"CREATE NAMESPACE IF NOT EXISTS $namespace;")
      spark.sql(f"USE $namespace")

      val now: LocalDateTime = LocalDateTime.now
      val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddmmHHss")
      val formatted: String = now.format(formatter)

      val simpleTableName: String = this.getClass.getSimpleName.replace("$", "_")
      val tableName: String = simpleTableName + formatted

      def syntheticData(numRows: Int, numPartitions: Int = 4): Seq[Datum] = {
        Seq.range(0, numRows).map((i: Int) => Datum(
          i,
          s"label_$i",
          i % numPartitions,
          new Date(now.toEpochSecond(UTC) + (i * 3600 * 1000 * 24)),
          new Timestamp(now.toEpochSecond(UTC) + (i * 3600 * 1000)),
          i),
        )
      }

      val tableNameFQN = catalog_name + "." + namespace + "." + tableName
      spark.createDataFrame(syntheticData(10)).writeTo(tableNameFQN).create()
      println("finished without error...")
  }
}
