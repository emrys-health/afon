package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeReadingGcpViaAwsFlowDocument extends AbstractFlowDocument {

    public SnowflakeReadingGcpViaAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "snowflake-reading-gcp");
    }

    @Override
    public String getFilename() {
        return "flow_snowflake_reading_gcp.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS Snowflake stack, then run Spark writing to GCP and Snowflake reading its catalog
                """;
    }

}
