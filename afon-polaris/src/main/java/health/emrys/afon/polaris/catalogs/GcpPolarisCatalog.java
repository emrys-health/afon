package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonProperty.GCP_SERVICE_ACCOUNT;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class GcpPolarisCatalog extends PolarisCatalog {

    private final String catalogName;
    private final boolean adminGrants;
    private final String bucketUrl;
    private final String serviceAccount;

    /**
     * Builds any "internal"-typed GCP catalog purely from its {@link AfonCatalog} definition; the
     * service account is fixed per cloud and read here directly.
     */
    public GcpPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        this.catalogName    = catalog.toString();
        this.adminGrants    = catalog.adminGrants();
        this.bucketUrl      = gsBucketUrl(catalog.bucketName());
        this.serviceAccount = properties.getValue(GCP_SERVICE_ACCOUNT);
    }

    public StorageConfigInfo storageConfigInfo() {
        return GcpUtils.createGcsStorageConfigInfo(bucketUri(), serviceAccount);
    }

    @Override
    public String catalogName() {
        return catalogName;
    }

    @Override
    public boolean adminGrants() {
        return adminGrants;
    }

    protected String bucketUri() {
      return bucketUrl;
    }

}
