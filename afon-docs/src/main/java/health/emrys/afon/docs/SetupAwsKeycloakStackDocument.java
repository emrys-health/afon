package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.SetupAwsKeycloakStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class SetupAwsKeycloakStackDocument extends AbstractDocument {

    public SetupAwsKeycloakStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "setup_aws_keycloak_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup an AWS KeyCloak stack populated with the realm, clients and users
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
        return new SetupAwsKeycloakStackMain().stepsUsing(stepOperations);
    }

}
