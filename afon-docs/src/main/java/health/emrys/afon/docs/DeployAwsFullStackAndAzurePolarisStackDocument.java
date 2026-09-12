package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployAwsFullStackAndAzurePolarisStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployAwsFullStackAndAzurePolarisStackDocument extends AbstractDocument {

    public DeployAwsFullStackAndAzurePolarisStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Deploy AWS Full stack and Azure Polaris stack.
            """;
    }

    @Override
    public String getFilename() {
        return "deploy_aws_full_stack_and_azure_polaris_stack.md";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stacks?
            Generate and run cleanup scripts:
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args aws
            ```
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args azure
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAwsFullStackAndAzurePolarisStackMain().stepsUsing(stepOperations);
    }

}
