package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static health.emrys.afon.config.AfonCloud.GCP;

public class DeleteGcpStackDocument extends AbstractStackDocument {

    public DeleteGcpStackDocument(AfonPropertyProvider properties) {
        super(properties, GCP);
    }

    @Override
    public String getFilename() {
        return "delete_gcp_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Deletes the GCP stack";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting
            
            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            afon-cli.sh generate-cleanup-script gcp
            ```
            """,
                properties.getValue(CLUSTER_NAME)
        );
    }

}