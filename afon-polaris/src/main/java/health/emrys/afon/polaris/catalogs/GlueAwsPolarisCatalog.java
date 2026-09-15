package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum;
import org.apache.polaris.core.admin.model.*;
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

/**
 * A one-off catalog shape (bespoke Glue/SigV4 connection) registered explicitly in
 * PolarisCatalogManager under the "glue" type, rather than being reducible to the generic
 * "internal"/"external" shapes. Built purely from its {@link AfonCatalog} definition, same as the
 * generic shapes; only the Glue role ARN and region are fixed per-cloud constants.
 */
public class GlueAwsPolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String roleArn;
    private final String region;
    private final String catalogName;
    private final boolean adminGrants;

    public GlueAwsPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        this.bucketUrl = s3BucketUrl(catalog.bucketName());
        this.roleArn = properties.getValue(AWS_GLUE_ROLE_ARN);
        this.region  = properties.getValue(AWS_REGION);
        this.catalogName = catalog.toString();
        this.adminGrants = catalog.adminGrants();
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
                roleArn(),
                null,
                null,
                region(),
                "glue",
                AuthenticationTypeEnum.SIGV4
            );

        ConnectionConfigInfo connectionInfo = new ConnectionConfigInfo(
            ConnectionTypeEnum.ICEBERG_REST,
            "https://glue." + region() + ".amazonaws.com/iceberg",
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
                AwsUtils.createS3StorageConfigInfo(roleArn(), bucketUri(), region()));
        return catalog;
    }

    protected String roleArn() {
        return roleArn;
    }

    protected String bucketUri() {
        return bucketUrl;
    }

    protected String region() {
        return region;
    }

    @Override
    public String catalogName() {
        return catalogName;
    }

    @Override
    public boolean adminGrants() {
        return adminGrants;
    }

    @Override
    protected StorageConfigInfo storageConfigInfo() {
        return AwsUtils.createS3StorageConfigInfo(roleArn, bucketUrl, region);
    }
}
