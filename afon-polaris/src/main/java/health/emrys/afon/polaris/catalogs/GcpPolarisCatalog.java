package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCloud;
import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GcpPolarisCatalog extends PolarisCatalog {

    private final String bucket;
    private final String serviceAccount;

    public GcpPolarisCatalog(Properties properties) {
      this.bucket         = toBucketUri(GCP_BUCKET_NAME.getValueFrom(properties));
      this.serviceAccount = GCP_SERVICE_ACCOUNT.getValueFrom(properties);
    }

  public static @NonNull String toBucketUri(String bucketName) {
    return "gs://" + bucketName;
  }


    public GcpStorageConfigInfo storageConfigInfo() {
        return GcpStorageConfigInfo
            .builder()
            .setGcsServiceAccount(serviceAccount)
            .setStorageType(GCS)
            .setAllowedLocations(List.of(bucketUri()))
            .build();
    }

    @Override
    public String catalogName() {
        return AfonCloud.GCP.toString();
    }

    protected String bucketUri() {
      return bucket;
    }

}
