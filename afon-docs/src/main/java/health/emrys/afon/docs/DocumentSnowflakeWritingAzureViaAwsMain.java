package health.emrys.afon.docs;

import java.io.IOException;
import java.util.List;

import health.emrys.afon.ops.DeploySnowflakeWritingAzureViaAwsMain;
import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;

public class DocumentSnowflakeWritingAzureViaAwsMain extends AbstractDocumentMetadata {
    @Override
    public String getSupplementaryIntro() {
        return """
                ## What it does
                This e2e test has Snowlfake write to Azure blob storage, registers its
                the metadata with Polaris then runs Spark against Polaris to see the same data.
                
                """;
    }

    @Override
    public String getFilename() {
        return "snowflake_to_azure.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeWritingAzureViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeWritingAzureViaAwsMain()).writeDocumentation();
    }
}
