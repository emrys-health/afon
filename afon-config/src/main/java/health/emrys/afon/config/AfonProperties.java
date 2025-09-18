package health.emrys.afon.config;

import org.slf4j.Logger;

public class AfonProperties {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AfonProperties.class);

    public static final String PROJECT_PROPERTIES_FILE = "afon.properties";

    public static final AfonProperty AWS_S3_BUCKET_NAME = new AfonProperty("AWS_S3_BUCKET_NAME");
    public static final AfonProperty AWS_S3_ROLE_ARN    = new AfonProperty("AWS_S3_ROLE_ARN");
    public static final AfonProperty AWS_WAREHOUSE_NAME = new AfonProperty("AWS_WAREHOUSE_NAME");
    public static final AfonProperty AWS_REGION         = new AfonProperty("AWS_REGION");
}
