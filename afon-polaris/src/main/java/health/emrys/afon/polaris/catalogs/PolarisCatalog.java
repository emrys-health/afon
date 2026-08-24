package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;

public abstract class PolarisCatalog {

    public static final String METADATA_KEY = "AFON_METADATA";

    private final PolarisSerialiser serialiser;

    protected PolarisCatalog() {
        this.serialiser = new PolarisSerialiser();
    }

    public String catalogAdminGrants() throws IncorrectConfigException {
        return serialiser.catalogAdminGrants();
    }

    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog();
        return toJson(new CreateCatalogRequest(catalog));
    }

    public boolean catalogContainsKey(String key) {
        return createCatalog().getProperties().containsKey(key);
    }

    public abstract String catalogName();

    /**
     * By default, allow admin grants.
     * Override with {@code false} it not the case.
     * @return flag the catalog allows admin grants
     */
    public boolean adminGrants() {
        return true;
    }

    protected String toJson(Object object) throws IncorrectConfigException {
        return serialiser.toJson(object);
    }

    protected Catalog createCatalog() {
        CatalogProperties catalogProperties = catalogProperties();
        return new Catalog(
            Catalog.TypeEnum.INTERNAL,
            catalogName(),
            catalogProperties,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            null,
            storageConfigInfo()
        );
    }

    protected @NonNull CatalogProperties catalogProperties() {
        CatalogProperties catalogProperties = new CatalogProperties(bucketUri());
        catalogProperties.putAll(metadata());
        return catalogProperties;
    }

    protected Map<String, String> metadata() {
        var properties = new HashMap<String, String>();
        properties.put(METADATA_KEY, "Afon catalog for " + catalogName());
        return properties;
    }

    protected abstract String bucketUri();

    protected abstract StorageConfigInfo storageConfigInfo();

}
