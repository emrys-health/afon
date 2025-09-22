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

    public static final AfonProperty AWS_S3_BUCKET_NAME    = new AfonProperty("AWS_S3_BUCKET_NAME");
    public static final AfonProperty AWS_S3_ROLE_ARN       = new AfonProperty("AWS_S3_ROLE_ARN");
    public static final AfonProperty AWS_REGION            = new AfonProperty("AWS_REGION");
    public static final AfonProperty AWS_ACCESS_KEY_ID     = new AfonProperty("AWS_ACCESS_KEY_ID");
    public static final AfonProperty AWS_SECRET_ACCESS_KEY = new AfonProperty("AWS_SECRET_ACCESS_KEY");

    public static final AfonProperty GCP_BUCKET_NAME     = new AfonProperty("GCP_BUCKET_NAME");
    public static final AfonProperty GCP_SERVICE_ACCOUNT = new AfonProperty("GCP_SERVICE_ACCOUNT");

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
