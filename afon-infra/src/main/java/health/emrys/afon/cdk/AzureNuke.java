package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;

import java.util.Properties;

import static java.lang.String.format;

public class AzureNuke {

    private final String jupyterDomainName;
    private final String polarisDomainName;
    private final String resourceGroup;
    private final String subscription;

    public AzureNuke() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public AzureNuke(Properties props) {
        polarisDomainName = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(props);
        jupyterDomainName = AfonProperties.AZURE_JUPYTER_DOMAIN_NAME.getValueFrom(props);
        resourceGroup     = AfonProperties.AZURE_RESOURCE_GROUP.getValueFrom(props);
        subscription      = AfonProperties.AZURE_SUBSCRIPTION.getValueFrom(props);
    }

    public String cleanUpHint() {
        String cleanGroup = format(
            """
            az group delete --name afon_resource_group_spark-cluster --subscription %s
            """,
            subscription
                                  );
        return format(
            """
            To clean common DNS issues:
            '''
            %s
            '''
            or:
            ```
            %s
            ```
            To clean a group that lingers after destroying the AKS cluster:
            '''
            %s
            '''
            """,
            deleteARecordFor(polarisDomainName),
            deleteARecordFor(jupyterDomainName),
            cleanGroup);
    }

    private @NonNull String deleteARecordFor(String domainName) {
        return format(
            """
            az network dns record-set a delete \\
            --resource-group %s \\
            --zone-name %s \\
            --name "@" \\
            --yes""", resourceGroup, domainName);
    }

}
