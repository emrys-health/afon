package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonCatalog.GCP;
import static health.emrys.afon.config.AfonProperty.GCP_BUCKET_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_SERVICE_ACCOUNT;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class GcpPolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String serviceAccount;

    public GcpPolarisCatalog(AfonPropertyProvider properties) {
      this.bucketUrl = gsBucketUrl(properties.getValue(GCP_BUCKET_NAME));
      this.serviceAccount = properties.getValue(GCP_SERVICE_ACCOUNT);
    }

    public StorageConfigInfo storageConfigInfo() {
        return GcpUtils.createGcsStorageConfigInfo(bucketUri(), serviceAccount);
    }

    @Override
    public String catalogName() {
        return GCP.toString();
    }

    protected String bucketUri() {
      return bucketUrl;
    }

}
