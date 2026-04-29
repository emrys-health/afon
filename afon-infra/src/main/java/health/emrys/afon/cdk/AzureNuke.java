package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;

import java.util.Properties;

import static java.lang.String.format;

public class AzureNuke {

    private String domainName;
    private String resourceGroup;

    public AzureNuke() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public AzureNuke(Properties props) {
        domainName    = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(props);
        resourceGroup = AfonProperties.AZURE_RESOURCE_GROUP.getValueFrom(props);
    }

    public String cleanUpHint() {
        return format(
            """
            az network dns record-set a delete \\
            --resource-group %s \\
            --zone-name %s \\
            --name "@" \\
            --yes""", resourceGroup, domainName);
    }

}
