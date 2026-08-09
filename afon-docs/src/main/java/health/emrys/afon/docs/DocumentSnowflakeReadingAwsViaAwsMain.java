package health.emrys.afon.docs;

import health.emrys.afon.ops.DeploySnowflakeReadingAwsViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;

public class DocumentSnowflakeReadingAwsViaAwsMain extends AbstractDocumentMetadata {

    @Override
    public String getFilename() {
        return "snowflake_reads_aws.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AWS);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeReadingAwsViaAwsMain().stepsUsing(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingAwsViaAwsMain()).writeDocumentation();
    }
}
