package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;

import java.util.Properties;

import static java.lang.String.format;

public class AzureNuke {

    private String domainName;
    private String resourceGroup;
    private String subscription;

    public AzureNuke() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public AzureNuke(Properties props) {
        domainName    = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(props);
        resourceGroup = AfonProperties.AZURE_RESOURCE_GROUP.getValueFrom(props);
        subscription  = AfonProperties.AZURE_SUBSCRIPTION.getValueFrom(props);
    }

    public String cleanUpHint() {
        String cleanDns = format(
            """
            az network dns record-set a delete \\
            --resource-group %s \\
            --zone-name %s \\
            --name "@" \\
            --yes""", resourceGroup, domainName);
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
                    To clean a group that lingers after destroying the AKS cluster:
                    '''
                    %s
                    '''
                    """,
                    cleanDns, cleanGroup);
    }

}
