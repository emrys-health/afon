package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperties;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AzureCloudConfigForPolaris extends CloudConfigForPolaris {
    private final String bucketUrl;
    private final String tenant;
    private final String multiTenantAppName;
    private final String clientId;

    public AzureCloudConfigForPolaris(Properties properties) {
        super(new JsonMaker());
        String bucketName     = AZURE_BUCKET.getValueFrom(properties);
        String storageAccount = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        this.bucketUrl          = bucketUrl(bucketName, storageAccount);
        this.tenant             = AZURE_TENANT_ID.getValueFrom(properties);
        this.multiTenantAppName = AZURE_CLIENT_ID.getValueFrom(properties);
        this.clientId           = AZURE_CLIENT_ID.getValueFrom(properties);
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog(bucketUrl, getStorageConfigInfo());
        return toJson(new CreateCatalogRequest(catalog));
    }

    public StorageConfigInfo getStorageConfigInfo() {
        return AzureUtils.getStorageConfigInfo(tenant,
                                               multiTenantAppName,
                                               bucketUrl,
                                               clientId);
    }

    @Override
    public String warehouseName() {
        return AfonProperties.SupportedCloudsEnum.AZURE.toString();
    }
}
