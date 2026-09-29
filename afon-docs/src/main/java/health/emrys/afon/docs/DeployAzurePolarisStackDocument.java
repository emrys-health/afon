package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.DEPLOY;

public class DeployAzurePolarisStackDocument extends AbstractStackDocument {

    public DeployAzurePolarisStackDocument(AfonPropertyProvider properties) {
        super(properties, DEPLOY, "azure-polaris");
    }

    @Override
    public String getFilename() {
        return "deploy_azure_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Deploy an Azure Polaris stack
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            afon-cli.sh generate-cleanup-script azure
            ```
            """);
    }

}
