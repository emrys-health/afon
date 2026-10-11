package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeReadingAzureViaAwsFlowDocument extends AbstractFlowDocument {

    public SnowflakeReadingAzureViaAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "snowflake-reading-azure");
    }

    @Override
    public String getFilename() {
        return "flow_snowflake_reading_azure.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS Snowflake stack, then run Spark writing to Azure and Snowflake reading its catalog
                """;
    }

}
