package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentGcpMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentGcpMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentGcpMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Creates a GCP environment with a Polaris instance populated with the test catalogs
            """;
    }

    @Override
    public String getFilename() {
        return "gcp_polaris.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployGcpMain.steps(stepOperations);
    }

}
