package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeleteAwsStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeleteAwsStackDocument extends AbstractDocument {

    public DeleteAwsStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "delete_aws_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Deletes the AWS stack.";
    }

    public String getFurtherNotes() {
        return String.format(
            """
            # Troubleshooting
            
            ### Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args aws
            ```
            """);
    }


    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeleteAwsStackMain().stepsUsing(stepOperations);
    }

}
