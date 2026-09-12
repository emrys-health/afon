package health.emrys.afon.docs;

import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class SetupAwsFullStackWithAzurePolarisHostingExternalCatalogDocument extends AbstractDocument {

    public SetupAwsFullStackWithAzurePolarisHostingExternalCatalogDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Deploy AWS full stack with Azure Polaris hosting an external catalog
            """;
    }

    @Override
    public String getFilename() {
        return "setup_aws_full_with_azure_polaris_hosting_external_catalog.md";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            Run:
            ```shell
            %s
            ```
                        
            If all else fails, destroy the Kubernetes cluster directly in the 
            [Azure console](https://portal.azure.com/#view/Microsoft_Azure_KubernetesFleet/KubernetesHub.MenuView/~/clusters)
            """, new AzureCleanupHelper(properties).cleanupCommands());
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain().stepsUsing(stepOperations);
    }

}
