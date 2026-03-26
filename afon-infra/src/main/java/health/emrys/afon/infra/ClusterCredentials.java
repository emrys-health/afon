package health.emrys.afon.infra;

public interface ClusterCredentials {
    String getAccessToken();

    String getPolarisId();

    String getPolarisSecret();

    String getPolarisHost();
}
