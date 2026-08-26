package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.GCP_BUCKET_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_SERVICE_ACCOUNT;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GcpPolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String serviceAccount;

    public GcpPolarisCatalog(AfonPropertyProvider properties) {
      this.bucketUrl = gsBucketUrl(properties.getValue(GCP_BUCKET_NAME));
      this.serviceAccount = properties.getValue(GCP_SERVICE_ACCOUNT);
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
      return bucketUrl;
    }

}
