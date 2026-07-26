package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;

import java.io.IOException;

import static health.emrys.afon.polaris.config.PolarisConfigurer.catalogsURI;

public class PurgePolarisMain {

    public static void main(String[] args) throws IncorrectConfigException, IOException {
        AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
        String                   domainName    = args[0];
        var                      client        = clientFactory.getPolarisClient(domainName, new AfonPropertiesLoader().getProperties());
        String                   accessToken   = client.getAccessToken();
        for (AfonProperties.SupportedCloudsEnum warehouse : AfonProperties.SupportedCloudsEnum.values()) {
            client.delete(catalogsURI(warehouse.toString()), accessToken);
        }
    }
}
