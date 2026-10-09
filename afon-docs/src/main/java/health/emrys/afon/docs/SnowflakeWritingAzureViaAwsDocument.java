package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class SnowflakeWritingAzureViaAwsDocument extends AbstractSnowflakeScenarioDocument {

    public SnowflakeWritingAzureViaAwsDocument(AfonPropertyProvider properties) { super(properties, "writing-azure"); }

    @Override
    public String getFilename() {
        return "snowflake_writes_to_azure.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return """
                ## What it does
                This e2e test has Snowflake writing to Azure blob storage, registering its
                metadata with Polaris then running Spark against Polaris to see the same data.

                """;
    }

}
