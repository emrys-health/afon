package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum;
import org.apache.polaris.core.admin.model.*;
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class GlueAwsPolarisCatalog extends AwsPolarisCatalog {

    private final String catalogName;

    public GlueAwsPolarisCatalog(AfonPropertyProvider properties) {
        super(properties.getValue(GLUE_S3_ROLE_ARN),
              bucketToUrl(properties.getValue(AWS_S3_EXTERNAL_BUCKET_NAME)),
                properties.getValue(AWS_REGION));
        this.catalogName = properties.getValue(GLUE_CATALOG_NAME);
    }

    protected @NonNull CatalogProperties catalogProperties() {
        CatalogProperties bucketProperties = CatalogProperties
            .builder()
            .setDefaultBaseLocation(bucketUri()) // needs to be different to any other Polaris knows about
            .build();
        return bucketProperties;
    }

    private @NonNull ConnectionConfigInfo connectionConfigInfo() {
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
        return connectionInfo;
    }

    protected @NonNull Catalog createCatalog() {
        Catalog catalog =
            new ExternalCatalog(
                    connectionConfigInfo(),
                Catalog.TypeEnum.EXTERNAL,
                    catalogName,
                    catalogProperties(),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                null,
                AwsUtils.getStorageConfigInfo(getRoleArn(), bucketUri(), getRegion()));
        return catalog;
    }

    @Override
    public String catalogName() {
        return catalogName;
    }
}
