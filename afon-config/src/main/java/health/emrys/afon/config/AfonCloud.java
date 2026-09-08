package health.emrys.afon.config;

/**
 * Enum representing different cloud environments supported by Afon.
 */
public enum AfonCloud {
    AWS("aws"),
    AZURE("azure"),
    GCP("gcp");

    private String value;

    AfonCloud(String value) {
        this.value = value;
    }

    public String toString() {
        return String.valueOf(value);
    }
}
