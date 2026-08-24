package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

public class DeletePolarisCatalogsMain {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(DeletePolarisCatalogsMain.class);

    private final PolarisRestClient client;
    private final AfonPropertyProvider properties;
    private final String accessToken;

    public DeletePolarisCatalogsMain(AfonPropertyProvider properties) {
        this.properties = properties;
        this.client = PolarisRestClientFactory.clientFactory().createClient(this.properties, "https");
        this.accessToken = client.getAccessToken();
    }

    private void deleteAll() {
        for (AfonCloud id : AfonCloud.values()) {
            delete(catalogsURI(id.toString()));
        }
    }

    private void delete(String uri) {
        logger.info("Deleting uri {}", uri);
        try {
            client.delete(uri, accessToken);
        } catch (IOException e) {
            logger.error("Failed to delete uri {}", uri, e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        String domainName;
        String catalogName = null;
        String namespace = null;
        String tableName = null;
        if (args.length == 1 ) {
            domainName = args[0];
        } else if (args.length == 2 ) {
            domainName = args[0];
            catalogName = args[1];
        } else if (args.length == 3 ) {
            domainName = args[0];
            catalogName = args[1];
            namespace = args[2];
        } else if (args.length == 4 ) {
            domainName = args[0];
            catalogName = args[1];
            namespace = args[2];
            tableName = args[3];
        } else {
            System.out.printf("%s requires arguments: domain-name", DeletePolarisCatalogsMain.class.getSimpleName());
            System.out.printf("%s requires arguments: domain-name catalog-name", DeletePolarisCatalogsMain.class.getSimpleName());
            System.out.printf("%s requires arguments: domain-name catalog-name namespace", DeletePolarisCatalogsMain.class.getSimpleName());
            System.out.printf("%s requires arguments: domain-name catalog-name namespace table-name", DeletePolarisCatalogsMain.class.getSimpleName());
            throw new IllegalArgumentException();
        }

        var properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, domainName);
        DeletePolarisCatalogsMain app = new DeletePolarisCatalogsMain(properties);
        try {
            if ( catalogName == null ) {
                logger.info("Deleting all catalogs for domain {}", domainName);
                app.deleteAll();
            } else {
                logger.info("Deleting catalog {} for domain {}", catalogName, domainName);
                app.delete(catalogsURI(catalogName));
            }
            if ( namespace != null ) {
                logger.info("Deleting namespace {}.{} for catalog {} and domain {}", namespace, catalogName, domainName);
                app.delete(namespaceURI(catalogName, namespace));
            }
            if ( namespace != null &&  tableName != null ) {
                logger.info("Deleting table {}.{} for catalog {} and domain {}", namespace, tableName, catalogName, domainName);
                app.delete(tableURI(catalogName, namespace, tableName));
            }
        } catch (Exception e) {
            logger.error("Failed {} action with args {}", DeletePolarisCatalogsMain.class.getSimpleName(), List.of(args), e);
            throw new RuntimeException(e);
        }

    }

}
