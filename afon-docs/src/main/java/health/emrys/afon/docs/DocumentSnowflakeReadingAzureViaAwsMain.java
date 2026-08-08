package health.emrys.afon.docs;

import health.emrys.afon.ops.DeploySnowflakeReadingAzureViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;

public class DocumentSnowflakeReadingAzureViaAwsMain extends AbstractDocumentMetadata {

    @Override
    public String getFilename() {
        return "snowflake_reads_azure.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AZURE);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeReadingAzureViaAwsMain().stepsUsing(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingAzureViaAwsMain()).writeDocumentation();
    }
}
