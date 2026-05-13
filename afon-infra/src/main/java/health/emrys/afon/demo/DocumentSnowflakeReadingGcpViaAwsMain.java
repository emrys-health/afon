package health.emrys.afon.demo;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

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
