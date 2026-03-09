package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperties;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class ExternalAzureCloudConfigForPolaris extends ExternalCloudConfigForPolaris {
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
        return AfonProperties.SupportedCloudsEnum.AZURE + "_EXTERNAL";
    }
}
