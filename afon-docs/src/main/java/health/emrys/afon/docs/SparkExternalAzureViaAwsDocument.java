package health.emrys.afon.docs;

import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.List;

public class SparkExternalAzureViaAwsDocument extends AbstractScenarioDocument {

    public SparkExternalAzureViaAwsDocument(AfonPropertyProvider properties) {
        super(properties, "aws-full", List.of("aws-full", "azure-polaris"), "external-azure");
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Spark, running locally, writes through the AWS Polaris to an external catalog hosted by the Azure Polaris:
            - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
            - a smoke test that ensures that Spark can talk to the AWS catalog that is a proxy for the Azure one
            """;
    }

    @Override
    public String getFilename() {
        return "spark_external_azure_via_aws.md";
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the Azure stack?
            Run:
            ```shell
            %s
            ```

            If all else fails, destroy the Kubernetes cluster directly in the
            [Azure console](https://portal.azure.com/#view/Microsoft_Azure_KubernetesFleet/KubernetesHub.MenuView/~/clusters)
            """, new AzureCleanupHelper(properties).cleanupCommands());
    }

}
