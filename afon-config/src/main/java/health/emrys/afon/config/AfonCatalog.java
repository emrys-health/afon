package health.emrys.afon.config;

/**
 * Enum representing different catalogs supported by Afon.
 */
public enum AfonCatalog {
    AWS("aws"),
    AWS_FORBIDDEN("aws_forbidden"),
    AWS_TRINO("aws_trino"),
    AZURE("azure"),
    AZURE_EXTERNAL("azure_external"),
    AZURE_FORBIDDEN("azure_forbidden"),
    GCP("gcp"),
    GCP_EXTERNAL("gcp_external"),
    GCP_FORBIDDEN("gcp_forbidden");

    private String value;

    AfonCatalog(String value) {
        this.value = value;
    }

    public String toString() {
        return String.valueOf(value);
    }
}
