package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;

public class AzureExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final @NonNull String catalogName;
    private final @NonNull String bucketUrl;
    private final @NonNull String tenant;
    private final @NonNull String multiTenantAppName;
    private final @NonNull String clientId;
    private final @NonNull String endpoint;

    /**
     * Builds any "external"-typed Azure catalog purely from its {@link AfonCatalog} definition;
     * credentials and domain are fixed per cloud and read here directly.
     */
    public AzureExternalPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        super(properties);
        this.catalogName        = catalog.toString();
        this.bucketUrl          = abfssBucketUrl(catalog.bucketName(), properties.getValue(AZURE_STORAGE_ACCOUNT));
        this.tenant             = properties.getValue(AZURE_TENANT_ID);
        this.multiTenantAppName = properties.getValue(AZURE_APP_ID);
        this.clientId           = properties.getValue(AZURE_APP_ID);
        this.endpoint           = "https://" + properties.getValue(AZURE_POLARIS_DOMAIN_NAME);
    }

    @Override
    protected String remoteCatalogName() {
        return "azure";
    }


    @Override
    protected @NonNull String endpoint() {
        return endpoint;
    }

    @Override
    protected @NonNull String bucketUri() {
        return bucketUrl;
    }

    protected @NonNull StorageConfigInfo storageConfigInfo() {
        return AzureUtils.createAzureStorageConfigInfo(tenant, multiTenantAppName, bucketUrl, clientId);
    }

    public String catalogName() {
        return catalogName;
    }
}
