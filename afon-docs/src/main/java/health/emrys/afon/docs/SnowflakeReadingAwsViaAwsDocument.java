package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonCloud.AWS;

public class SnowflakeReadingAwsViaAwsDocument extends AbstractScenarioDocument {

    public SnowflakeReadingAwsViaAwsDocument(AfonPropertyProvider properties) { super(properties, "aws-snowflake", "reading-aws"); }

    @Override
    public String getFilename() {
        return "snowflake_reads_aws.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AWS);
    }

}
