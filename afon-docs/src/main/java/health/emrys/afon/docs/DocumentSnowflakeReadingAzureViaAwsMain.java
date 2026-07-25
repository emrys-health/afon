package health.emrys.afon.docs;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;

import health.emrys.afon.demo.DeploySnowflakeReadingAzureViaAwsMain;
import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;

public class DocumentSnowflakeReadingAzureViaAwsMain extends AbstractDocumentMetadata {

    @Override
    public String getSupplementaryIntro() {
        return DocumentSnowflakeUtils.getSupplementaryIntro(AZURE);
    }

    @Override
    public String getFilename() {
        return "snowflake_reads_azure.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeReadingAzureViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingAzureViaAwsMain()).writeDocumentation();
    }
}
