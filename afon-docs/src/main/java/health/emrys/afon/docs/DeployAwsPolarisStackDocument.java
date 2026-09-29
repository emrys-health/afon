package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.DEPLOY;

public class DeployAwsPolarisStackDocument extends AbstractStackDocument {

    public DeployAwsPolarisStackDocument(AfonPropertyProvider properties) {
        super(properties, DEPLOY, "aws-polaris");
    }

    @Override
    public String getFilename() {
        return "deploy_aws_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Deploys an AWS Polaris stack
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
