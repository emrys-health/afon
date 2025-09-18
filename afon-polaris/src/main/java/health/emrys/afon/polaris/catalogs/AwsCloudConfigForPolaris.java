package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.polaris.core.admin.model.*;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class AwsCloudConfigForPolaris implements CloudConfigForPolaris {

    private final String warehouseName;
    private final String roleArn;
    private final String bucket;
    private final String region;
    private final JsonMaker jsonMaker;

    public AwsCloudConfigForPolaris(Properties properties) {
        this.warehouseName = AWS_WAREHOUSE_NAME.getValueFrom(properties);
        this.roleArn = AWS_S3_ROLE_ARN.getValueFrom(properties);
        this.bucket = AWS_S3_BUCKET_NAME.getValueFrom(properties);
        this.region = AWS_REGION.getValueFrom(properties);
        this.jsonMaker = new JsonMaker();
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        CatalogProperties catalogProperties = new CatalogProperties(bucket);
        StorageConfigInfo storageConfigInfo = new AwsStorageConfigInfo(
                roleArn,
                null,
                null,
                region,
                StorageConfigInfo.StorageTypeEnum.S3,
                List.of(bucket)
        );
        Catalog catalog = new Catalog(
                Catalog.TypeEnum.INTERNAL,
                warehouseName,
                catalogProperties,
                storageConfigInfo
        );
        Catalogs catalogs = new Catalogs(List.of(catalog));
        try {
            return jsonMaker.toJson(catalogs);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

    @Override
    public String catalogAdminGrants() {
        return "";
    }

    @Override
    public String catalogRoles() {
        return "";
    }

    @Override
    public String warehouseName() {
        return warehouseName;
    }

    @Override
    public String toString() {
        return "AwsCloudConfigForPolaris{" +
                "warehouseName='" + warehouseName + '\'' +
                ", roleArn='" + roleArn + '\'' +
                ", bucket='" + bucket + '\'' +
                ", region='" + region + '\'' +
                ", jsonMaker=" + jsonMaker +
                '}';
    }
}
