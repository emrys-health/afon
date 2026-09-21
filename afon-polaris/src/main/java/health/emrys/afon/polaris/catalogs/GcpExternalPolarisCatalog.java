package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.GCP_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_SERVICE_ACCOUNT;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GcpExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String catalogName;
    private final String bucketUrl;
    private final String serviceAccount;
    private final String endpoint;

    /**
     * Builds any "external"-typed GCP catalog purely from its {@link AfonCatalog} definition; the
     * service account and domain are fixed per cloud and read here directly.
     */
    public GcpExternalPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        super(properties);
        this.catalogName    = catalog.toString();
        this.bucketUrl      = gsBucketUrl(catalog.bucketName());
        this.serviceAccount = properties.getValue(GCP_SERVICE_ACCOUNT);
        this.endpoint       = "https://" + properties.getValue(GCP_POLARIS_DOMAIN_NAME);
    }

    @Override
    protected String remoteCatalogName() {
        return "gcp";
    }

    @Override
    protected List<String> scopes() {
        return List.of(OAUTH_SCOPE);
    }

    @Override
    protected @NonNull String bucketUri() {
        return bucketUrl;
    }

    @Override
    protected @NonNull String endpoint() {
        return endpoint;
    }

    @Override
    protected @NonNull StorageConfigInfo storageConfigInfo() {
        return GcpStorageConfigInfo
            .builder()
            .setGcsServiceAccount(serviceAccount)
            .setStorageType(GCS)
            .setAllowedLocations(List.of(bucketUrl))
            .build();
    }

    @Override
    public String catalogName() {
        return catalogName;
    }
}
