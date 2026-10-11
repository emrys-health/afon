package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeReadingAwsFlowDocument extends AbstractFlowDocument {

    public SnowflakeReadingAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "snowflake-reading-aws");
    }

    @Override
    public String getFilename() {
        return "flow_snowflake_reading_aws.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS Snowflake stack, then run Snowflake reading the AWS catalog
                """;
    }

}
