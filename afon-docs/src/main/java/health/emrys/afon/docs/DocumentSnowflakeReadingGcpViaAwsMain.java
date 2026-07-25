package health.emrys.afon.docs;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

import health.emrys.afon.demo.DeploySnowflakeReadingGcpViaAwsMain;
import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;

public class DocumentSnowflakeReadingGcpViaAwsMain extends AbstractDocumentMetadata {

    @Override
    public String getSupplementaryIntro() {
        return DocumentSnowflakeUtils.getSupplementaryIntro(GCP);
    }

    @Override
    public String getFilename() {
        return "snowflake_reads_gcp.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeReadingGcpViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingGcpViaAwsMain()).writeDocumentation();
    }
}
