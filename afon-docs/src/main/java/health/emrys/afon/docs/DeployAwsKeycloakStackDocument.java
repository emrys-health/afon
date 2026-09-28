package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployAwsKeycloakStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployAwsKeycloakStackDocument extends AbstractDocument {

    public DeployAwsKeycloakStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "deploy_aws_keycloak_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Deploys an AWS KeyCloak stack, ie only the KeyCloak server, without Polaris
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args aws
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAwsKeycloakStackMain().stepsUsing(stepOperations);
    }

}
