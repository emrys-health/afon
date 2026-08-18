package health.emrys.afon.docs;

import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployAzureFullStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployAzureFullStackDocument extends AbstractDocument {

    public DeployAzureFullStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "deploy_azure_full_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Brings up the Azure full stack (Jupyter, Polaris, Spark etc).";
    }

    public String getFurtherNotes() {
        String polarisDomainName =
            properties.getValue(AfonProperty.AZURE_DOMAIN_NAME);
        return String.format(
            """
            # Troubleshooting
            
            ### Problems starting Spark Connect
            
            There appears to be a race condition so that Spark Connect is killed as soon as it starts
            (see issue [#65](https://github.com/emrys-health/afon-core/issues/65)). 
            If this happens, run:
            
            ```
            kubectl delete sparkapp spark-connector
            %s
            ```
            
            ### Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           ./afon.sh -a exec --exec-class cdk.azure.AzureCleanupHelper --exec-args azure-cleanup.sh
            ```
            """,
            SparkWorkloads.startConnectCommand(polarisDomainName));
    }


    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAzureFullStackMain().stepsUsing(stepOperations);
    }

}
