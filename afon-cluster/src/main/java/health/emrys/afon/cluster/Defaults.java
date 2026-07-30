package health.emrys.afon.cluster;

public interface Defaults {

    String GCP_REGION = "europe-west2";

    String JUPYTER_SERVICE_NAME = "jupyter-web";
    String JUPYTER_INGRESS      = "jupyter-ingress";

    String POLARIS_POD_NAME     = "polaris";
    String POLARIS_INGRESS_NAME = "polaris-ingress";

    String SPARK_SERVICE_NAME         = "spark-master-lb";
    String SPARK_CONNECTOR_NAME       = "spark-connector";
    String SPARK_SERVICE_ACCOUNT_NAME = "spark-application-sa";

    String TRINO_SERVICE_NAME = "trino";

    String DUCKDB_POD_NAME     = "duckdb";
    String DUCKDB_SERVICE_NAME = "duckdb";
}
