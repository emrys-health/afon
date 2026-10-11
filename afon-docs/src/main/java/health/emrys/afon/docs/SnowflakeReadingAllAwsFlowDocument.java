package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeReadingAllAwsFlowDocument extends AbstractFlowDocument {

    public SnowflakeReadingAllAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "snowflake-reading-all");
    }

    @Override
    public String getFilename() {
        return "flow_snowflake_reading_all.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Run every Snowflake reading scenario, ie reading the AWS, Azure and GCP catalogs, on the AWS Snowflake stack, which must be set up first with `manage-stack setup aws-snowflake`
                """;
    }

}
