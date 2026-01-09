package health.emrys.afon.acceptance.aws

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object GcpRest {

  def main(args: Array[String]): Unit = {
    println(s"Using GOOGLE_APPLICATION_CREDENTIALS = ${System.getenv("GOOGLE_APPLICATION_CREDENTIALS")}")
    val catalog_name = "CATALOG_NAME"
    val spark = SparkSession.builder.config(new SparkConf().setMaster("local[2]").setAppName("Tests")
        .set(f"spark.sql.catalog.$catalog_name", "org.apache.iceberg.spark.SparkCatalog")
        .set(f"spark.sql.catalog.$catalog_name.type", "rest")
        .set(f"spark.sql.catalog.$catalog_name.uri", "https://biglake.googleapis.com/iceberg/v1/restcatalog")
        .set(f"spark.sql.catalog.$catalog_name.warehouse", "gs://emrys_afon_core")
        .set(f"spark.sql.catalog.$catalog_name.header.x-goog-user-project", "fjords-450014")
        .set(f"spark.sql.catalog.$catalog_name.rest.auth.type", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
        .set(f"spark.sql.catalog.$catalog_name.io-impl", "org.apache.iceberg.gcp.gcs.GCSFileIO")
        .set(f"spark.sql.catalog.$catalog_name.rest-metrics-reporting-enabled", "false")
        .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .set("spark.sql.defaultCatalog", catalog_name))
      .getOrCreate()

      val namespace = "ph_namespace"
      spark.sql("SHOW NAMESPACES IN emrys_afon_core").show()
      spark.sql(f"CREATE NAMESPACE IF NOT EXISTS $namespace;")
      spark.sql(f"USE $namespace")
      println("finished without error...")
  }
}
