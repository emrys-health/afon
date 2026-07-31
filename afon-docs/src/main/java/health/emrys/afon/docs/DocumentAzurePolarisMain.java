package health.emrys.afon.docs;

import health.emrys.afon.cdk.azure.AzureNuke;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;
import static org.slf4j.LoggerFactory.getLogger;

public class DocumentAzurePolarisMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentAzurePolarisMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentAzurePolarisMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            """;
    }

    @Override
    public String getFilename() {
        return "azure_polaris.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteStack(AZURE),
            stepOperations.deployAzurePolaris(),
            stepOperations.waitForAzurePolaris(),
            stepOperations.initializeAllCatalogsInAzure()
                      );
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Problems running `tofu destroy`?
            
            If the domain name points at a Kubernetes service that's not longer there, run:
            ```shell
            %s
            ```
            """, new AzureNuke().cleanUpHint());
    }

}
