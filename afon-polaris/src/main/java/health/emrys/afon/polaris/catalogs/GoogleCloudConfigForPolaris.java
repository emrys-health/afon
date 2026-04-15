package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.GcpStorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum.GCS;

public class GoogleCloudConfigForPolaris extends CloudConfigForPolaris {

    private final String bucket;
    private final String serviceAccount;

    public GoogleCloudConfigForPolaris(Properties properties) {
        super(new JsonMaker());
      this.bucket         = toBucketUri(GCP_BUCKET_NAME.getValueFrom(properties));
      this.serviceAccount = GCP_SERVICE_ACCOUNT.getValueFrom(properties);
    }

  public static @NonNull String toBucketUri(String bucketName) {
    return "gs://" + bucketName;
  }

  @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog(getBucket(), getStorageConfigInfo());
        return toJson(new CreateCatalogRequest(catalog));
    }

    public GcpStorageConfigInfo getStorageConfigInfo() {
        return GcpStorageConfigInfo
            .builder()
            .setGcsServiceAccount(serviceAccount)
            .setStorageType(GCS)
            .setAllowedLocations(List.of(getBucket()))
            .build();
    }

    @Override
    public String warehouseName() {
        return SupportedCloudsEnum.GCP.toString();
    }

    public String getBucket() {
      return bucket;
    }

}
