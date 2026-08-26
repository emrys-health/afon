package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonCloud.GCP_EXTERNAL;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GcpExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String bucketUrl;
    private final String serviceAccount;
    private final String endpoint;

    public GcpExternalPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl      = gsBucketUrl(properties.getValue(GCP_BUCKET_NAME));
        this.serviceAccount = properties.getValue(GCP_SERVICE_ACCOUNT);
        this.endpoint       = "https://" + properties.getValue(GCP_DOMAIN_NAME);
    }

    @Override
    protected String remoteCatalogName() {
        return GCP.toString();
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
        return GCP_EXTERNAL.toString();
    }
}
