package health.emrys.afon.demo;

import health.emrys.afon.cdk.azure.AzureNuke;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentExternalCatalogsMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentExternalCatalogsMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentExternalCatalogsMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Local Spark calls AWS Polaris that defers to Azure Polaris.
            This sets the cloud environments up just as they were for the TDA demo of 23/3/26.
            """;
    }

    @Override
    public String getFilename() {
        return "aws_azure_external_catalogs.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployExternalCatalogsMain.steps(stepOperations);
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Problems destroying the stack?
            If the domain name points at a Kubernetes service that's not longer there, run:
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
            """, new AzureNuke().cleanUpHint());
    }
}
