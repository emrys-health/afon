package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentExternalCatalogs implements DocumentMetadata {

    private static final Logger log = getLogger(DocumentExternalCatalogs.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentExternalCatalogs()).writeDocumentation();
    }

    @Override
    public String getDescription() {
        return "Local Spark calls AWS Polaris that defers to Azure Polaris.";
    }

    @Override
    public String getFilename() {
        return "aws_azure_external_catalogs.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployExternalCatalogsMain.steps(stepOperations);
    }
}
