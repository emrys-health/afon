package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GcpUtils {

    public static @NonNull String gsBucketUrl(String bucketName) {
        return "gs://" + bucketName;
    }

    public static StorageConfigInfo createGcsStorageConfigInfo(String bucketUri, String serviceAccount) {
        return GcpStorageConfigInfo
                .builder()
                .setGcsServiceAccount(serviceAccount)
                .setStorageType(GCS)
                .setAllowedLocations(List.of(bucketUri))
                .build();
    }
}
