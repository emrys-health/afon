package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonCloud.GCP;

public class SnowflakeReadingGcpViaAwsDocument extends AbstractSnowflakeScenarioDocument {

    public SnowflakeReadingGcpViaAwsDocument(AfonPropertyProvider properties) { super(properties, "reading-gcp"); }

    @Override
    public String getFilename() {
        return "snowflake_reads_gcp.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(GCP);
    }

}
