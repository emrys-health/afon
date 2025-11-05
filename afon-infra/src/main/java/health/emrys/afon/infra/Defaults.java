package health.emrys.afon.infra;

public interface Defaults {

    String CLUSTER_NAME = "spark-cluster";
    String AWS_REGION   = "eu-west-2";

    String JUPYTER_SERVICE_NAME = "jupyter-lb";

    String POLARIS_POD_NAME     = "polaris";
    String POLARIS_SERVICE_NAME = "polaris-lb";

    String SPARK_SERVICE_NAME = "spark-master-lb";

}
