package health.emrys.afon.cluster;

public interface ClusterCredentials {
    String getAccessToken();

    String getPolarisId();

    String getPolarisSecret();

    String getPolarisHost();
}
