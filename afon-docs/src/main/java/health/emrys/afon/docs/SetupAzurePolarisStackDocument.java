package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.SETUP;

public class SetupAzurePolarisStackDocument extends AbstractStackDocument {

    public SetupAzurePolarisStackDocument(AfonPropertyProvider properties) {
        super(properties, SETUP, "azure-polaris");
    }

    @Override
    public String getFilename() {
        return "setup_azure_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup an Azure Polaris stack populated with the test catalogs
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            afon-cli.sh generate-cleanup-script azure-polaris
            ```
            """);
    }

}
