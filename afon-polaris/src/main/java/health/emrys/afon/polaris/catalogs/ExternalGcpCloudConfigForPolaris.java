package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.GCP_BUCKET_NAME;
import static health.emrys.afon.config.AfonProperties.GCP_SERVICE_ACCOUNT;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP_EXTERNAL;
import static health.emrys.afon.config.CatalogUtils.OAUTH_SCOPE;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class ExternalGcpCloudConfigForPolaris extends ExternalCloudConfigForPolaris {
    private final String bucket;
    private final String serviceAccount;

    public ExternalGcpCloudConfigForPolaris(Properties properties) {
        super(properties);
        this.bucket         = "gs://" + GCP_BUCKET_NAME.getValueFrom(properties);
        this.serviceAccount = GCP_SERVICE_ACCOUNT.getValueFrom(properties);
    }

    @Override
    protected String getRemoteCatalogName() {
        return GCP.toString();
    }

    @Override
    protected List<String> getScopes() {
        return List.of(OAUTH_SCOPE);
    }

    @Override
    protected @NonNull String getEndpoint() {
        return "https://emryspolarisgcp.click";
    }

    @Override
    protected @NonNull String getBucketUri() {
        return bucket;
    }

    @Override
    protected @NonNull StorageConfigInfo getStorageConfigInfo() {
        return GcpStorageConfigInfo
            .builder()
            .setGcsServiceAccount(serviceAccount)
            .setStorageType(GCS)
            .setAllowedLocations(List.of(bucket))
            .build();
    }

    @Override
    public String warehouseName() {
        return GCP_EXTERNAL.toString();
    }
}
