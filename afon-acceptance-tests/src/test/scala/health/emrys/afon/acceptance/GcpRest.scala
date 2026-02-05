package health.emrys.afon.acceptance

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import uk.co.odinconsultants.documentation_utils.Datum

import java.sql.{Date, Timestamp}
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter

/**
 * Works if a file like:
 * /home/henryp/.config/gcloud/application_default_credentials.json
 * exists or you point GOOGLE_APPLICATION_CREDENTIALS at it
 */
object GcpRest {

  def main(args: Array[String]): Unit = {
    System.setProperty("GOOGLE_APPLICATION_CREDENTIALS",
    """{ "type": "service_account", "project_id": "fjords-450014", "private_key_id": "5613266e1d64474ef12208e4530444454b307492", "private_key": "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDR3nyQ+fX4SxOK\nz5UkJ598tdlcgOIevB/qKjQRp7AbLGmpezNqCZSTm4q37ZrdLSWFeWDxDe3agqkC\nkstkuNhmg1rhurs22rCLJdtXxXmS/ED4z/xsowPnpOsOBsHQJwaFS4ZUsAFR+Wj1\nt6JWi5mF4H8fWBhNUOGyCE22+5EcvsBSQavh5hoIPmL2GYhSqXdyKhUJOey/HXde\nBgFaYb/DSTfIwrx9tiQLwuMywDe/KqYpZgzaZUqqc7bkcJSsrzouWZGSHsdLUE01\nnrAEA9RZ8jH+yFVVEwsqQp8vawdoeaHxfrjxXooTS+sAEeZWgpoUpDriue/eNesM\nUAdFwdXpAgMBAAECggEADg75ePQ3mwb7c4VhjBsX4tCjM4BCNXUcbbEfTQ32vPFC\nEDt7p5cl8+fb2GOTJ80ICguGGAu68tPlGrIm7BO6Uk6rCjs6Iovuftm9umDU+qjJ\neOlk00mtv6kTYlyHQ+hzVj9Hi7mBEyLSGkCvgBzonzhZR6oc8aPxBH4sJ41tTHen\nJhn65gD+Q0iTy5k2c9Oo85NnVj04fhGKVGcBq+YRxom+9RwY2kh4J/CtLc5JIuMW\npUhG9k6oYAkRr8NfRDKbxw5KWIq2EAamCh+zfjYPWSkIqTLXJb28SjsweZB03aSd\nKr3VP0XrV446BMghc8ztBDK7v532tYnqzQBoq4WqcQKBgQDtUaqrQUqYliITXbIA\n6CyFBa03+vbRd7XR4v7EKpoYX1DBzYB+yTFxvAyWtvCV5PnIGAMp+uyQx7txF+nL\nj6qGV1Ah1xgVPJi4hqakBeL0vQpKqnuUhCrQF2KgIbpcvh7o0owXpwPpLs+FWKq4\nHBqg2ikozl3m55PneqsqusDc0QKBgQDiY6my7xaLYRP5kuhw1uClO7IdiK+vlJRt\n4B900o2SUZurymd0Oiq1gl+s9KqKnKaOHTpunyVMls6m2wkgl8f6gz6ugbVwIOI/\nThrUsgyTw7DQ26pVCOxegClVmLnkNPyYfSFLoVUPaT7OqPYkcWM1uliNeHoxXACK\ndypDfaNNmQKBgQDsjSPOclVVxQiQPyuIBeUibucbTrepUWbVoCsMA973fkKz+xAi\n0I7NbIEr1Yv4DXYAwhadSLV3Fu+6qfhr5Hc4U13XbVxfBri/7c+63Pc9thTFGiv2\nfMtkAntXXGczvHp43i2wAdU/IF7P7vK0c6cDQJIYT6N/3sMpz7c/70kAkQKBgD3f\n72mwXwEJL2Ru+oicshl3D19zFFFw0oCVWQOc6IStOVbioxPpMfMaBvxGgrYPypXc\n9zN028TrEuAXzY7S7R1S8bbQTL6wviV/O2YWo8lfGtx6rTtAjRnm/WUfsO7mbINg\n79Cz74OeyEPDekLLmsn8fGduHjePZbZyi2KW3CNBAoGAdgK6f76Jur6FZlLjOzL/\nwiXHhV6WFI//6HW2lz/kSmaLFg0BCpvsaBS9mQa+dRQUF5bK2QdruzykoCLn9jY2\n9kRsPbnnfy9BZsu+JJSPuOhwjOAjes37mA6Ltn/adR9tTHZNDKXbHtNQrdlbmtA8\nT45GwZinAfQ4YDw/8oWMC1Q=\n-----END PRIVATE KEY-----\n", "client_email": "my-vendable-sa@fjords-450014.iam.gserviceaccount.com", "client_id": "103905077815523834346", "auth_uri": "https://accounts.google.com/o/oauth2/auth", "token_uri": "https://oauth2.googleapis.com/token", "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs", "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/my-vendable-sa%40fjords-450014.iam.gserviceaccount.com", "universe_domain": "googleapis.com", "quota_project_id": "fjords-450014" }""");
    println(s"Using GOOGLE_APPLICATION_CREDENTIALS = ${System.getenv("GOOGLE_APPLICATION_CREDENTIALS")}")
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
