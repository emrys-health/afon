package health.emrys.afon.docs;

import health.emrys.afon.ops.DeploySnowflakeReadingGcpViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.GCP;

public class DocumentSnowflakeReadingGcpViaAwsMain extends AbstractDocumentMetadata {

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

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingGcpViaAwsMain()).writeDocumentation();
    }
}
