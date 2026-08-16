package health.emrys.afon.docs;

import health.emrys.afon.ops.DeploySnowflakeReadingGcpViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.GCP;

public class DeploySnowflakeReadingGcpViaAwsDocument extends AbstractDocument {

    public DeploySnowflakeReadingGcpViaAwsDocument(Properties properties) { super(properties); }

    @Override
    public String getFilename() {
        return "snowflake_reads_gcp.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(GCP);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeReadingGcpViaAwsMain().stepsUsing(stepOperations);
    }

}
