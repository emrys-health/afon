package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeleteAzurePolarisStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeleteAzurePolarisStackDocument extends AbstractDocument {

    public DeleteAzurePolarisStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "delete_azure_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Deletes an Azure environment with a Polaris instance
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class cdk.azure.AzureCleanupHelper --exec-args azure-cleanup.sh
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeleteAzurePolarisStackMain().stepsUsing(stepOperations);
    }

}
