package health.emrys.afon.infra;

public interface Defaults {

    String GCP_REGION   = "europe-west2";

    String JUPYTER_SERVICE_NAME = "jupyter-web";

    String POLARIS_POD_NAME     = "polaris";
    String POLARIS_SERVICE_NAME = "polaris-ingress";

    String SPARK_SERVICE_NAME         = "spark-master-lb";
    String SPARK_CONNECTOR_NAME       = "spark-connector";
    String SPARK_SERVICE_ACCOUNT_NAME = "spark-application-sa";
}
