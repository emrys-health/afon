package health.emrys.afon.docs;

import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployExternalCatalogsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployExternalCatalogsDocument extends AbstractDocument {

    public DeployExternalCatalogsDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Local Spark calls AWS Polaris that defers to Azure Polaris.
            """;
    }

    @Override
    public String getFilename() {
        return "aws_azure_external_catalogs.md";
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
            
            If you see an error like:
            ```
            │ Error: A resource with the ID "/subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group_aks" already exists - to be managed via Terraform this resource needs to be imported into the State. Please see the resource documentation for "azurestack_resource_group" for more information.
            │\s
            │   with azurestack_resource_group.XXX,
            │   on cdk.tf.json line 330, in resource.azurestack_resource_group.XXX:
            ```
            you need to run
            ```shell
            tofu import azurestack_resource_group.XXX /subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group_aks
            ```
            replacing XXX with whatever was being used.
            If that import fails with something like `│ Error: Invalid provider configuration` then
            temporarily comment out the offending providers (the offending lines in the json will be given)
            and try again. 
            
            Alternatively, you can delete it with:
            ```shell
            az group delete \\
              --name afon_resource_group_aks \\
              --yes \\
              --no-wait
            ```
            and it will be created again on the next run.
            
            If all else fails, destroy the Kubernetes cluster directly in the 
            [Azure console](https://portal.azure.com/#view/Microsoft_Azure_KubernetesFleet/KubernetesHub.MenuView/~/clusters)
            """, new AzureCleanupHelper(properties).cleanupCommands());
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployExternalCatalogsMain().stepsUsing(stepOperations);
    }

}
