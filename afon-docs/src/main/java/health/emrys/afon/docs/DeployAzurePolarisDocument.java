package health.emrys.afon.docs;

import health.emrys.afon.ops.DeployAzurePolarisMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Properties;

public class DeployAzurePolarisDocument extends AbstractDocument {

    public DeployAzurePolarisDocument(Properties properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "azure_polaris.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return "";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class cdk.azure.AzureCleanupHelper --exec-args azure-cleanup.sh
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAzurePolarisMain().stepsUsing(stepOperations);
    }

}
