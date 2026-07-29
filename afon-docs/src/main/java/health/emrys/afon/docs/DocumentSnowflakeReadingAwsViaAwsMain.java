package health.emrys.afon.docs;

import java.io.IOException;
import java.util.List;

import health.emrys.afon.ops.DeploySnowflakeReadingAwsViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;

public class DocumentSnowflakeReadingAwsViaAwsMain extends AbstractDocumentMetadata {

    @Override
    public String getSupplementaryIntro() {
        return DocumentSnowflakeUtils.getSupplementaryIntro(AWS);
    }

    @Override
    public String getFilename() {
        return "snowflake_reads_aws.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeReadingAwsViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingAwsViaAwsMain()).writeDocumentation();
    }
}
