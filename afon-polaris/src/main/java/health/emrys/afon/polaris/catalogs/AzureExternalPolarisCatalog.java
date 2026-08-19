package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;

/**
 * This helps to create a catalog called AfonCloud.AZURE_EXTERNAL_BUCKET
 * in whichever Polaris is the current that points to a remove catalog called
 * AfonCloud.AZURE in emryspolarisazure.click
 */
public class AzureExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final @NonNull String bucketUrl;
    private final @NonNull String tenant;
    private final @NonNull String multiTenantAppName;
    private final @NonNull String clientId;
    private final @NonNull String domainName;

    public AzureExternalPolarisCatalog(Properties properties) {
        super(properties);
        String storageAccount   = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        String bucketName       = AZURE_EXTERNAL_BUCKET.getValueFrom(properties);
        this.bucketUrl          = bucketUrl(bucketName, storageAccount);
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
        this.domainName         = "https://" + AfonProperty.AZURE_DOMAIN_NAME.getValueFrom(properties);
    }

    @Override
    protected String remoteCatalogName() {
        return AfonCloud.AZURE.toString();
    }

    @Override
    protected List<String> scopes() {
        return List.of(OAUTH_SCOPE);
    }

    @Override
    protected @NonNull String endpoint() {
        return domainName;
    }

    @Override
    protected @NonNull String bucketUri() {
        return bucketUrl;
    }

    protected @NonNull StorageConfigInfo storageConfigInfo() {
        return AzureUtils.getStorageConfigInfo(tenant, multiTenantAppName, bucketUrl, clientId);
    }

    public String catalogName() {
        return AfonCloud.AZURE_EXTERNAL.toString();
    }
}
