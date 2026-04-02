package health.emrys.afon.demo;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.polaris.comms.PolarisConfigurer.getDeleteURI;

public class DeleteCatalogsMain {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(DeleteCatalogsMain.class);
    private final String accessToken;
    private final AfonPolarisClient client;

    public static void main(String[] args) {
        DeleteCatalogsMain app = new DeleteCatalogsMain();
        if (args.length == 0) {
            app.deleteAll();
        } else {
          for (String arg : args) {
              app.delete(arg);
          }
        }
    }

    DeleteCatalogsMain() {
        client      = new AfonPolarisClient(new AfonPropertiesLoader().getProperties(),
                                                         "https");
        accessToken = client.getAccessToken();
    }

    private void deleteAll() {
        for (SupportedCloudsEnum warehouse: SupportedCloudsEnum.values()) {
            String warehouseName = warehouse.toString();
            delete(warehouseName);
        }
    }

    private void delete(String warehouseName) {
        logger.info(String.format("Deleting catalog %s", warehouseName));
        try {
            client.delete(getDeleteURI(warehouseName), accessToken);
        } catch (IOException e) {
            logger.error("Error while deleting catalog " + warehouseName, e);
        }
    }

}
