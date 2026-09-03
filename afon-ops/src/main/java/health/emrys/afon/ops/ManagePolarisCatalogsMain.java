package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisCatalogPurger;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static org.slf4j.LoggerFactory.getLogger;

public class ManagePolarisCatalogsMain {

    private static final Logger logger   = getLogger(ManagePolarisCatalogsMain.class);

    public static final String CREATE = "create";
    public static final String DELETE = "delete";
    public static final String RECREATE = "recreate";

    private final AfonPropertyProvider properties;
    private final PolarisCatalogPurger purger;

    public ManagePolarisCatalogsMain(AfonPropertyProvider properties) {
        this.properties = properties;
        String            endpoint = properties.getValue(POLARIS_ENDPOINT);
        PolarisRestClient client   = PolarisRestClientFactory.clientFactory().createClient(properties);
        logger.debug("Polaris client for endpoint {}: clientId={}, clientSecret={}", endpoint, client.getClientId(), client.getClientSecret());
        purger = new PolarisCatalogPurger(client, endpoint);
    }

    public void createAllCatalogs() throws IncorrectConfigException, IOException {
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(properties);
        config.configure();
    }

    public void deleteAllCatalogs() throws IOException, ParseException {
        for (AfonCloud id : List.of(AZURE, AWS, GCP)) {
            purger.deleteCatalog(id.toString());
        }
    }

    private void deleteNamespace(String catalogName, String namespace) throws IOException {
        purger.deleteNamespace(catalogName, namespace);
    }

    private void deleteTable(String catalogName, String namespace, String tableName) throws IOException {
        purger.deleteTable(catalogName, namespace, tableName);
    }

    private void deleteCatalog(String catalogName) throws IOException, ParseException {
        purger.deleteCatalog(catalogName);
    }

    public static void main(String[] args) {
        String action;
        String domainName;
        String catalogName = null;
        String namespace   = null;
        String tableName   = null;
        if (args.length == 2) {
            action     = args[0];
            domainName = args[1];
        } else if (args.length == 3) {
            action      = args[0];
            domainName  = args[1];
            catalogName = args[2];
        } else if (args.length == 4) {
            action      = args[0];
            domainName  = args[1];
            catalogName = args[2];
            namespace   = args[3];
        } else if (args.length == 5) {
            action      = args[0];
            domainName  = args[1];
            catalogName = args[2];
            namespace   = args[3];
            tableName   = args[4];
        } else {
            String usage = String.format("""
                    
                    Invalid arguments: '%s'
                    usage:  %s supports the following args options:
                            [delete|create|recreate] polaris-domain-name
                            [delete] polaris-domain-name catalog-name [namespace] [table-name]
                    """, String.join(" ", args), ManagePolarisCatalogsMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }

        var properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, domainName);
        ManagePolarisCatalogsMain app = new ManagePolarisCatalogsMain(properties);
        try {
            if (action.equals(DELETE)) {
                if (catalogName == null) {
                    logger.info("Deleting all catalogs for domain {}", domainName);
                    app.deleteAllCatalogs();
                } else {
                    logger.info("Deleting catalog {} for domain {}", catalogName, domainName);
                    app.deleteCatalog(catalogName);
                }
                if (namespace != null && tableName == null) {
                    logger.info("Deleting namespace {} for catalog {} and domain {}", namespace, catalogName, domainName);
                    app.deleteNamespace(catalogName, namespace);
                }
                if (namespace != null && tableName != null) {
                    logger.info("Deleting table {}.{} for catalog {} and domain {}", namespace, tableName, catalogName, domainName);
                    app.deleteTable(catalogName, namespace, tableName);
                }
            }
            if (action.equals(CREATE)) {
                logger.info("Creating all catalogs for domain {}", domainName);
                app.createAllCatalogs();
            }
            if (action.equals(RECREATE)) {
                logger.info("Deleting and recreating all catalogs for domain {}", domainName);
                app.deleteAllCatalogs();
                app.createAllCatalogs();
            }
        } catch (Exception e) {
            logger.warn("Failed exec of {} with args {}", ManagePolarisCatalogsMain.class.getSimpleName(), List.of(args));
            throw new RuntimeException(e);
        }
    }

}
