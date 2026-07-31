package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperties;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;

/**
 * This helps to create a catalog called SupportedCloudsEnum.AZURE_EXTERNAL_BUCKET
 * in whichever Polaris is the current that points to a remove catalog called
 * SupportedCloudsEnum.AZURE in emryspolarisazure.click
 */
public class ExternalAzureCloudConfigForPolaris extends ExternalCloudConfigForPolaris {
    private final @NonNull String bucketUrl;
    private final @NonNull String tenant;
    private final @NonNull String multiTenantAppName;
    private final @NonNull String clientId;
    private final @NonNull String domainName;

    public ExternalAzureCloudConfigForPolaris(Properties properties) {
        super(properties);
        String storageAccount   = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        String bucketName       = AZURE_EXTERNAL_BUCKET.getValueFrom(properties);
        this.bucketUrl          = bucketUrl(bucketName, storageAccount);
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
        this.domainName         = "https://" + AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties);
    }

    @Override
    protected String getRemoteCatalogName() {
        return SupportedCloudsEnum.AZURE.toString();
    }

    @Override
    protected List<String> getScopes() {
        return List.of(OAUTH_SCOPE);
    }

    @Override
    protected @NonNull String getEndpoint() {
        return domainName;
    }

    @Override
    protected @NonNull String getBucketUri() {
        return bucketUrl;
    }

    protected @NonNull StorageConfigInfo getStorageConfigInfo() {
        return AzureUtils.getStorageConfigInfo(tenant, multiTenantAppName, bucketUrl, clientId);
    }

    public String warehouseName() {
        return SupportedCloudsEnum.AZURE_EXTERNAL.toString();
    }
}
