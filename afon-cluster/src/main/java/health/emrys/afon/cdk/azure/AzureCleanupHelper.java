package health.emrys.afon.cdk.azure;

import health.emrys.afon.cdk.AbstractCleanupHelper;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;

import java.util.Properties;

import static java.lang.String.format;

public class AzureCleanupHelper extends AbstractCleanupHelper {

    private final String jupyterDomainName;
    private final String polarisDomainName;
    private final String resourceGroup;
    private final String subscription;

    public AzureCleanupHelper() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public AzureCleanupHelper(Properties props) {
        polarisDomainName = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(props);
        jupyterDomainName = AfonProperties.AZURE_JUPYTER_DOMAIN_NAME.getValueFrom(props);
        resourceGroup     = AfonProperties.AZURE_RESOURCE_GROUP.getValueFrom(props);
        subscription      = AfonProperties.AZURE_SUBSCRIPTION.getValueFrom(props);
    }

    private @NonNull String cleanGroupCommand() {
        String cleanGroup = format(
            """
            az group delete --name afon_resource_group_spark-cluster --subscription %s
            """,
            subscription);
        return cleanGroup;
    }

    private @NonNull String deleteDnsRecordCommand(String domainName) {
        return format(
            """
            az network dns record-set a delete \\
            --resource-group %s \\
            --zone-name %s \\
            --name "@" \\
            --yes""", resourceGroup, domainName);
    }

    public String cleanupCommands() {
        return format(
                """
                #!/bin/bash
                # clean common DNS issues
                %s
                %s
                # clean group that lingers after destroying the AKS cluster
                %s
                """,
                deleteDnsRecordCommand(polarisDomainName),
                deleteDnsRecordCommand(jupyterDomainName),
                cleanGroupCommand());
    }

    public static void main(String[] args) {
        outputCleanupCommands(args, new AzureCleanupHelper().cleanupCommands());
    }

}
