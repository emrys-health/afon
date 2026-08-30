package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeleteAzureStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeleteAzureStackDocument extends AbstractDocument {

    public DeleteAzureStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "delete_azure_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return "Deletes the Azure stack";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args azure
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeleteAzureStackMain().stepsUsing(stepOperations);
    }

}
