package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

public class DeleteCatalogsMain {

    private static final Logger            logger = org.slf4j.LoggerFactory.getLogger(DeleteCatalogsMain.class);
    private final        String            accessToken;
    private final        AfonPolarisClient client;

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();

    DeleteCatalogsMain() {
        client      = clientFactory.getPolarisClient(
            new AfonPropertiesLoader().getProperties(),
            "https");
        accessToken = client.getAccessToken();
    }

    private void deleteAll() {
        for (SupportedCloudsEnum warehouse : SupportedCloudsEnum.values()) {
            String warehouseName = warehouse.toString();
            String uri           = catalogsURI(warehouseName);
            delete(uri);
        }
    }

    private void delete(String uri) {
        logger.info(String.format("Deleting catalog %s", uri));
        try {
            client.delete(uri, accessToken);
        } catch (IOException e) {
            logger.error("Error while deleting catalog " + uri, e);
        }
    }

    public static void main(String[] args) {
        DeleteCatalogsMain app = new DeleteCatalogsMain();
        if (args.length == 0) {
            app.deleteAll();
        } else if (args.length == 1) {
            String uri = catalogsURI(args[0]);
            app.delete(uri);
        } else if (args.length == 2) {
            String uri = namespaceURI(args[0], args[1]);
            app.delete(uri);
        } else if (args.length == 3) {
            String uri = tableURI(args[0], args[1], args[2]);
            app.delete(uri);
        }
    }

}
