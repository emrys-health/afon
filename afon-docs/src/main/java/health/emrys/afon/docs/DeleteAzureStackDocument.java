package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonCloud.AZURE;

public class DeleteAzureStackDocument extends AbstractStackDocument {

    public DeleteAzureStackDocument(AfonPropertyProvider properties) {
        super(properties, AZURE);
    }

    @Override
    public String getFilename() {
        return "delete_azure_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return "Deletes the Azure stack";
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
