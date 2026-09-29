package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.SETUP;

public class SetupAwsKeycloakStackDocument extends AbstractStackDocument {

    public SetupAwsKeycloakStackDocument(AfonPropertyProvider properties) {
        super(properties, SETUP, "aws-keycloak");
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
            afon-cli.sh generate-cleanup-script aws
            ```
            """);
    }

}
