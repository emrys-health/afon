package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonCloud.AZURE;

public class SnowflakeReadingAzureViaAwsDocument extends AbstractSnowflakeScenarioDocument {

    public SnowflakeReadingAzureViaAwsDocument(AfonPropertyProvider properties) { super(properties, "reading-azure"); }

    @Override
    public String getFilename() {
        return "snowflake_reads_azure.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AZURE);
    }

}
