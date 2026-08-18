package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeleteAzureFullStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeleteAzureFullStackDocument extends AbstractDocument {

    public DeleteAzureFullStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "delete_azure_full_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Brings down the Azure full stack (Jupyter, Polaris, Spark etc).";
    }

    public String getFurtherNotes() {
        return String.format(
            """
            # Troubleshooting
            
            ### Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           ./afon.sh -a exec --exec-class cdk.azure.AzureCleanupHelper --exec-args azure-cleanup.sh
            ```
            """);
    }


    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeleteAzureFullStackMain().stepsUsing(stepOperations);
    }

}
