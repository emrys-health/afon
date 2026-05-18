package health.emrys.afon.polaris.catalogs;

import java.util.Map;
import java.util.Properties;
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum;
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum;
import org.apache.polaris.core.admin.model.*;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class GlueCloudConfigForPolaris extends AwsCloudConfigForPolaris {

    private final String warehouseName;

    public GlueCloudConfigForPolaris(Properties properties) {
        super(GLUE_S3_ROLE_ARN.getValueFrom(properties),
              bucketToUrl(AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties)),
              AWS_REGION.getValueFrom(properties));
        this.warehouseName = GLUE_CATALOG_NAME.getValueFrom(properties);
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        CatalogProperties bucketProperties = CatalogProperties
            .builder()
            .setDefaultBaseLocation(getBucketUri()) // needs to be different to any other Polaris knows about
            .build();

        SigV4AuthenticationParameters glueIcebergRole =
            new SigV4AuthenticationParameters(
                getRoleArn(),
                null,
                null,
                getRegion(),
                "glue",
                AuthenticationTypeEnum.SIGV4
            );

        ConnectionConfigInfo connectionInfo = new ConnectionConfigInfo(
            ConnectionTypeEnum.ICEBERG_REST,
            "https://glue." + getRegion() + ".amazonaws.com/iceberg",
            glueIcebergRole,
            null,
            Map.of());

        ExternalCatalog catalog =
            new ExternalCatalog(
                connectionInfo,
                Catalog.TypeEnum.EXTERNAL,
                warehouseName,
                bucketProperties,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                null,
                AwsUtils.getStorageConfigInfo(getRoleArn(), getBucketUri(), getRegion()));

        return toJson(new CreateCatalogRequest(catalog));
    }

    @Override
    public String warehouseName() {
        return warehouseName;
    }
}
