package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.AfonPolarisClient;

import java.io.IOException;

import static health.emrys.afon.polaris.comms.PolarisConfigurer.catalogsURI;

public class PurgePolaris {
    public static void main(String[] args) throws IncorrectConfigException, IOException {
        String domainName = args[0];
        var    client      = new AfonPolarisClient(domainName, new AfonPropertiesLoader().getProperties());
        String accessToken = client.getAccessToken();
      for (AfonProperties.SupportedCloudsEnum warehouse : AfonProperties.SupportedCloudsEnum.values()) {
          client.delete(catalogsURI(warehouse.toString()), accessToken);
      }
    }
}
