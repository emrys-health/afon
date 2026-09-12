package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeleteGcpStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;

public class DeleteGcpStackDocument extends AbstractDocument {

    public DeleteGcpStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "delete_gcp_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Deletes the GCP stack";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting
            
            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args gcp
            ```
            """,
                properties.getValue(CLUSTER_NAME)
        );
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeleteGcpStackMain().stepsUsing(stepOperations);
    }

}