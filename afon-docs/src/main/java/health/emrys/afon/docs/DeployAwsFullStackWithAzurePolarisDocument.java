package health.emrys.afon.docs;

import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployAwsFullStackWithAzurePolarisMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployAwsFullStackWithAzurePolarisDocument extends AbstractDocument {

    public DeployAwsFullStackWithAzurePolarisDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Deploy AWS full stack with Azure Polaris.
            """;
    }

    @Override
    public String getFilename() {
        return "deploy_aws_full_with_azure_polaris_stacks.md";
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
        return new DeployAwsFullStackWithAzurePolarisMain().stepsUsing(stepOperations);
    }

}
