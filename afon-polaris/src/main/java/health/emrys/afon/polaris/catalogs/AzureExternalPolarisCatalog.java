package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;

/**
 * This helps to create a catalog called {@code azure_external}
 * in whichever Polaris is the current that points to a remote catalog called
 * {@code azure} in {@link AfonProperty#AZURE_POLARIS_DOMAIN_NAME}
 */
public class AzureExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final @NonNull String bucketUrl;
    private final @NonNull String tenant;
    private final @NonNull String multiTenantAppName;
    private final @NonNull String clientId;
    private final @NonNull String endpoint;

    public AzureExternalPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        String storageAccount   = properties.getValue(AZURE_STORAGE_ACCOUNT);
        String bucketName       = properties.getValue(AZURE_BUCKET_NAME_EXTERNAL);
        this.bucketUrl          = abfssBucketUrl(bucketName, storageAccount);
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
    protected List<String> scopes() {
        return List.of(OAUTH_SCOPE);
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
        return "azure_external";
    }
}
