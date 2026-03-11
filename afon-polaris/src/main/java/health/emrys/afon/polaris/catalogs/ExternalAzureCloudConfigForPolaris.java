package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;
import static health.emrys.afon.config.CatalogUtils.OAUTH_SCOPE;

public class ExternalAzureCloudConfigForPolaris extends ExternalCloudConfigForPolaris {
    public static final String EXTERNAL_AZURE_WAREHOUSE_NAME = SupportedCloudsEnum.AZURE + "_EXTERNAL";

    private final @NonNull String bucketUrl;
    private final @NonNull String tenant;
    private final @NonNull String multiTenantAppName;
    private final @NonNull String clientId;

    public ExternalAzureCloudConfigForPolaris(Properties properties) {
        super(properties);
        String storageAccount   = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        String bucketName       = AZURE_EXTERNAL_BUCKET.getValueFrom(properties);
        this.bucketUrl          = bucketUrl(bucketName, storageAccount);
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
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
        return "https://emryspolarisazure.click"; // TODO don't hardcode
    }

    @Override
    protected @NonNull String getBucketUrl() {
        return bucketUrl;
    }

    protected @NonNull StorageConfigInfo getStorageConfigInfo() {
        return AzureUtils.getStorageConfigInfo(tenant, multiTenantAppName, bucketUrl, clientId);
    }

    public String warehouseName() {
        return EXTERNAL_AZURE_WAREHOUSE_NAME;
    }
}
