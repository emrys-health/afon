package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.AzureStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static java.lang.String.format;

public class AzureUtils {

    public static StorageConfigInfo getStorageConfigInfo(String tenant,
                                                         String multiTenantAppName,
                                                         String bucketUrl,
                                                         String clientId) {
        return AzureStorageConfigInfo
            .builder()
            .setTenantId(tenant)
            .setMultiTenantAppName(multiTenantAppName)
            .setStorageType(StorageConfigInfo.StorageTypeEnum.AZURE)
            .setAllowedLocations(List.of(bucketUrl))
            .setConsentUrl(format("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=%s&response_type=code&redirect_uri=http://localhost:3000/redirect&response_mode=query&scope=https://graph.microsoft.com/.default&state=12345", clientId)) // not sure about this one....
            .build();
    }

    public static @NonNull String bucketUrl(String bucketName, String storageAccount) {
        return format("abfss://%s@%s.dfs.core.windows.net", bucketName, storageAccount);
    }
}
