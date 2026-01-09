package health.emrys.afon.acceptance.aws

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object GcpRest {

  def main(args: Array[String]): Unit = {
    println(s"Using GOOGLE_APPLICATION_CREDENTIALS = ${System.getenv("GOOGLE_APPLICATION_CREDENTIALS")}")
    val catalog_name = "CATALOG_NAME"
//    val authToken = "ya29.a0Aa7pCA8ZcXAlo74-RthWhPHvQUmXQFHl3UIrG65_5vhBfPTWdPPkWXlQ24f5-C89Xrq37P-eaHJafDPIGS6ZweD-2v_ROOibTWpGNUTpqmdIAfiUr6XicRGFisPrcLM_0Pis2HoGVMf3k7TJCooad-BrFViXQQGDC1py3inzfOSEf0vcPifb5Yfmi2_aI0wa7OFiLEcUTTRD9AaCgYKAZcSARASFQHGX2MiJVLyAGeCbbHM65IgCNKrfQ0213"
    val spark = SparkSession.builder.config(new SparkConf().setMaster("local[2]").setAppName("Tests")
        .set(f"spark.sql.catalog.$catalog_name", "org.apache.iceberg.spark.SparkCatalog")
        .set(f"spark.sql.catalog.$catalog_name.type", "rest")
        .set(f"spark.sql.catalog.$catalog_name.uri", "https://biglake.googleapis.com/iceberg/v1/restcatalog")
        .set(f"spark.sql.catalog.$catalog_name.warehouse", "gs://emrys_afon_core")
        .set(f"spark.sql.catalog.$catalog_name.header.x-goog-user-project", "fjords-450014")
//        .set(f"spark.sql.catalog.$catalog_name.header.Authorization: Bearer ", authToken)
        .set(f"spark.sql.catalog.$catalog_name.rest.auth.type", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
        .set(f"spark.sql.catalog.$catalog_name.io-impl", "org.apache.iceberg.gcp.gcs.GCSFileIO")
        .set(f"spark.sql.catalog.$catalog_name.rest-metrics-reporting-enabled", "false")
        .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
//        .set(f"spark.sql.catalog.$catalog_name.header.X-Iceberg-Access-Delegation", "vended-credentials")
//        .set(f"spark.sql.catalog.$catalog_name.oauth2.token", authToken)
        .set("spark.sql.defaultCatalog", catalog_name))
      .getOrCreate()

      spark.sql("CREATE NAMESPACE IF NOT EXISTS ph_namespace;")
  }
}
