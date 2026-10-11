package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeWritingAzureViaAwsFlowDocument extends AbstractFlowDocument {

    public SnowflakeWritingAzureViaAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "snowflake-writing-azure");
    }

    @Override
    public String getFilename() {
        return "flow_snowflake_writing_azure.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS Snowflake stack, then run Snowflake writing to Azure, with the table registered in the AWS Polaris and read by Spark
                """;
    }

}
