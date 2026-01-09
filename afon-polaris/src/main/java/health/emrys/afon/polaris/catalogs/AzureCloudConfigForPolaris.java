package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperties;
import org.apache.polaris.core.admin.model.AzureStorageConfigInfo;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static java.lang.String.format;

public class AzureCloudConfigForPolaris extends CloudConfigForPolaris {
    private final String bucket;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    public AzureCloudConfigForPolaris(Properties properties) {
        super(new JsonMaker());
        String bucketName     = AZURE_BUCKET.getValueFrom(properties);
        String storageAccount = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        this.bucket             = format("abfss://%s@%s.dfs.core.windows.net", bucketName, storageAccount);
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        StorageConfigInfo storageConfigInfo = getStorageConfigInfo();
        Catalog catalog = createCatalog(bucket, storageConfigInfo);
        return toJson(new CreateCatalogRequest(catalog));
    }

    public StorageConfigInfo getStorageConfigInfo() {
        StorageConfigInfo storageConfigInfo = new AzureStorageConfigInfo(
                tenant,
                multiTenantAppName,
                format("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=%s&response_type=code&redirect_uri=http://localhost:3000/redirect&response_mode=query&scope=https://graph.microsoft.com/.default&state=12345", clientId), // not sure about this one....
                StorageConfigInfo.StorageTypeEnum.AZURE,
                List.of(bucket)
        );
        return storageConfigInfo;
    }

    public String getBucketUri() { return bucket; }

    @Override
    public String warehouseName() {
        return AfonProperties.SupportedCloudsEnum.AZURE.toString();
    }
}
