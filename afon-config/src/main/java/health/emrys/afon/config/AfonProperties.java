package health.emrys.afon.config;

import org.slf4j.Logger;

public class AfonProperties {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AfonProperties.class);

    /**
     * General details live here
     */
    public static final String PROJECT_PROPERTIES_FILE = "afon.properties";
    /**
     * Sensitive details live in here.
     */
    public static final String ENV_PROPERTIES_FILE     = "env.properties";

    // AWS
    public static final AfonProperty AWS_S3_BUCKET_NAME    = new AfonProperty("AWS_S3_BUCKET_NAME");
    public static final AfonProperty AWS_S3_ROLE_ARN       = new AfonProperty("AWS_S3_ROLE_ARN");
    public static final AfonProperty AWS_REGION            = new AfonProperty("AWS_REGION");
    public static final AfonProperty AWS_ACCESS_KEY_ID     = new AfonProperty("AWS_ACCESS_KEY_ID");
    public static final AfonProperty AWS_SECRET_ACCESS_KEY = new AfonProperty("AWS_SECRET_ACCESS_KEY");

    // GCP
    public static final AfonProperty GCP_BUCKET_NAME                = new AfonProperty("GCP_BUCKET_NAME");
    public static final AfonProperty GCP_SERVICE_ACCOUNT            = new AfonProperty("GCP_SERVICE_ACCOUNT");
    public static final AfonProperty GOOGLE_APPLICATION_CREDENTIALS = new AfonProperty("GOOGLE_APPLICATION_CREDENTIALS");

    // AZURE
    public static final AfonProperty AZURE_BUCKET          = new AfonProperty("AZURE_BUCKET");
    public static final AfonProperty AZURE_STORAGE_ACCOUNT = new AfonProperty("AZURE_STORAGE_ACCOUNT");
    public static final AfonProperty AZURE_APP_ID          = new AfonProperty("AZURE_APP_ID");
    // needed by Polaris:
    public static final AfonProperty AZURE_TENANT_ID       = new AfonProperty("AZURE_TENANT_ID");
    public static final AfonProperty AZURE_CLIENT_ID       = new AfonProperty("AZURE_CLIENT_ID");
    public static final AfonProperty AZURE_CLIENT_SECRET   = new AfonProperty("AZURE_CLIENT_SECRET");

    public static final AfonProperty POLARIS_ID       = new AfonProperty("POLARIS_ID");
    public static final AfonProperty POLARIS_SECRET   = new AfonProperty("POLARIS_SECRET");
    public static final AfonProperty POLARIS_ENDPOINT = new AfonProperty("POLARIS_ENDPOINT");

    public enum SupportedCloudsEnum {
        AWS("aws"), GCP("gcp"), AZURE("azure");

        private String value;

        SupportedCloudsEnum(String value) {
            this.value = value;
        }

        public String toString() {
            return String.valueOf(value);
        }
    }
}
