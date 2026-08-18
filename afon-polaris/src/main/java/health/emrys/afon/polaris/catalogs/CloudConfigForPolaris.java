package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.HashMap;
import java.util.Map;

public abstract class CloudConfigForPolaris {

    public static final String METADATA_KEY = "EMRYS_METADATA";

    private final PolarisSerialiser serialiser;

    protected CloudConfigForPolaris(JsonSerialiser jsonSerialiser) {
        this.serialiser = new PolarisSerialiser(jsonSerialiser);
    }

    public abstract String catalogConfig() throws IncorrectConfigException;

    public abstract String warehouseName();

    protected String toJson(Object object) throws IncorrectConfigException {
        return serialiser.toJson(object);
    }

    protected Catalog createCatalog(String bucket,
                                    StorageConfigInfo storageConfigInfo) {
        CatalogProperties catalogProperties = new CatalogProperties(bucket);
        catalogProperties.putAll(metadata());
        return new Catalog(
            Catalog.TypeEnum.INTERNAL,
            warehouseName(),
            catalogProperties,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            null,
            storageConfigInfo
        );
    }
    public Catalog createCatalog() {
        return createCatalog(getBucketUri(), getStorageConfigInfo());
    }
    abstract protected StorageConfigInfo getStorageConfigInfo();
    abstract protected String getBucketUri();

    protected Map<String, String> metadata() {
        var properties = new HashMap<String, String>();
        properties.put(METADATA_KEY, "Emrys catalog for " + warehouseName());
        return properties;
    }

    public PolarisSerialiser getSerialiser() {
        return serialiser;
    }
}
