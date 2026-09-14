package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisCatalogManager;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static org.slf4j.LoggerFactory.getLogger;

public class ManagePolarisCatalogsMain {

    private static final Logger logger   = getLogger(ManagePolarisCatalogsMain.class);

    public static final String CREATE = "create";
    public static final String DELETE = "delete";
    public static final String RECREATE = "recreate";

    private final PolarisCatalogManager catalogManager;
    private final AfonCatalogs afonCatalogs;
    private final String polarisDomain;

    public ManagePolarisCatalogsMain(AfonPropertyProvider properties) {
        this.catalogManager = new PolarisCatalogManager(properties);
        this.afonCatalogs = new AfonCatalogs(properties);
        this.polarisDomain = properties.getValue(POLARIS_ENDPOINT);
    }

    public void createAllCatalogs() throws IncorrectConfigException, IOException {
        logger.info("Creating all catalogs for domain {}", polarisDomain);
        catalogManager.configureAllCatalogs();
    }

    public void createCatalog(String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Creating catalog {} for domain {}", catalogName, polarisDomain);
        catalogManager.configureCatalogs(catalogName);
    }

    public void deleteAllCatalogs() throws IOException, ParseException {
        logger.info("Deleting all catalogs for domain {}", polarisDomain);
        for (AfonCatalog catalog : afonCatalogs.all()) {
            deleteCatalog(catalog.toString());
        }
    }

    public void deleteCatalog(String catalogName) throws IOException, ParseException {
        logger.info("Deleting catalog {} for domain {}", catalogName, polarisDomain);
        catalogManager.deleteCatalog(catalogName);
    }

    public void deleteNamespace(String catalogName, String namespace) throws IOException {
        logger.info("Deleting namespace {} for catalog {} and domain {}", namespace, catalogName, polarisDomain);
        catalogManager.deleteNamespace(catalogName, namespace);
    }

    public void deleteTable(String catalogName, String namespace, String tableName) throws IOException {
        logger.info("Deleting table {}.{} for catalog {} and domain {}", namespace, tableName, catalogName, polarisDomain);
        catalogManager.deleteTable(catalogName, namespace, tableName);
    }

    private void recreateAllCatalogs() throws IOException, ParseException, IncorrectConfigException {
        logger.info("Recreating all catalogs for domain {}", polarisDomain);
        deleteAllCatalogs();
        createAllCatalogs();
    }

    public static void main(String[] args) {
        String action;
        String cloudName;
        String catalogName = null;
        String namespace   = null;
        String tableName   = null;

        if (args.length == 2) {
            action  = args[0];
            cloudName  = args[1];
        } else if (args.length == 3) {
            action  = args[0];
            cloudName  = args[1];
            catalogName = args[2];
        } else if (args.length == 4) {
            action  = args[0];
            cloudName  = args[1];
            catalogName = args[2];
            namespace  = args[3];
        } else if (args.length == 5) {
            action  = args[0];
            cloudName  = args[1];
            catalogName = args[2];
            namespace  = args[3];
            tableName = args[4];
        } else {
            String usage = String.format("""
                    
                    Invalid arguments: '%s'
                    usage:  %s supports the following args options:
                            [delete|create|recreate] cloud
                            [delete|create] cloud catalog-name
                            [delete] cloud catalog-name [namespace] [table-name]
                    """, String.join(" ", args), ManagePolarisCatalogsMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(cloudName.toUpperCase());
        var properties = new OpsPropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, new PolarisEndpoint(cloud, properties).getEndpoint());

        ManagePolarisCatalogsMain app = new ManagePolarisCatalogsMain(properties);
        try {
            if (action.equals(DELETE)) {
                if (catalogName == null) {
                    app.deleteAllCatalogs();
                } else {
                    app.deleteCatalog(catalogName);
                }
                if (namespace != null && tableName == null) {
                    app.deleteNamespace(catalogName, namespace);
                }
                if (namespace != null && tableName != null) {
                    app.deleteTable(catalogName, namespace, tableName);
                }
            }
            if (action.equals(CREATE)) {
                if (catalogName == null) {
                    app.createAllCatalogs();
                } else {
                    app.createCatalog(catalogName);
                }
            }
            if (action.equals(RECREATE)) {
                app.recreateAllCatalogs();
            }
        } catch (Exception e) {
            logger.warn("Failed exec of {} with args {}", ManagePolarisCatalogsMain.class.getSimpleName(), List.of(args));
            throw new RuntimeException(e);
        }
    }

}
