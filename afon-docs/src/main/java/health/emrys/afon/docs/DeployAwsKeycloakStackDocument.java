package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.DEPLOY;

public class DeployAwsKeycloakStackDocument extends AbstractStackDocument {

    public DeployAwsKeycloakStackDocument(AfonPropertyProvider properties) {
        super(properties, DEPLOY, "aws-keycloak");
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
            afon-cli.sh generate-cleanup-script aws
            ```
            """);
    }

}
