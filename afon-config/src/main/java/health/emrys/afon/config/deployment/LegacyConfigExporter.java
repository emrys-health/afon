package health.emrys.afon.config.deployment;

import health.emrys.afon.config.AfonPropertiesLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

/**
 * Bridges the legacy flat-properties world into the declarative one: reads the
 * current {@code afon.properties} and emits the equivalent
 * {@code afon-deployment.yaml}. The output reproduces today's demo topology —
 * three clouds, the catalogs previously enumerated by {@code SupportedCloudsEnum},
 * Spark + Trino on AWS, Snowflake, and Jupyter on AWS.
 * <p>
 * Run with:
 * <pre>
 * mvn compile -Dexec.mainClass=health.emrys.afon.config.deployment.LegacyConfigExporter exec:java
 * </pre>
 * which prints the YAML to stdout; pass a file path as the first argument to
 * write it there instead.
 */
public final class LegacyConfigExporter {

    /**
     * {@code GcpConstruct} hardcodes its region rather than reading a property;
     * mirrored here until the GCP region becomes configurable.
     */
    static final String GCP_REGION = "europe-west2";

    private LegacyConfigExporter() {
    }

    public static AfonDeploymentConfig export(Properties p) {
        CloudSpec aws = new CloudSpec(
            "aws",
            CloudSpec.PROVIDER_AWS,
            AWS_REGION.getValueFrom(p),
            Map.of(
                "bucket", AWS_S3_BUCKET_NAME.getValueFrom(p),
                "roleArn", AWS_S3_ROLE_ARN.getValueFrom(p)),
            Map.of(
                "domain", AWS_POLARIS_DOMAIN_NAME.getValueFrom(p),
                "certArn", AWS_POLARIS_CERT_ARN.getValueFrom(p),
                "dnsZone", AWS_POLARIS_DNS_ZONE.getValueFrom(p)));

        CloudSpec azure = new CloudSpec(
            "azure",
            CloudSpec.PROVIDER_AZURE,
            AZURE_REGION.getValueFrom(p),
            Map.of(
                "storageAccount", AZURE_STORAGE_ACCOUNT.getValueFrom(p),
                "container", AZURE_BUCKET.getValueFrom(p)),
            Map.of(
                "domain", AZURE_DOMAIN_NAME.getValueFrom(p),
                "resourceGroup", AZURE_RESOURCE_GROUP.getValueFrom(p),
                "subscription", AZURE_SUBSCRIPTION.getValueFrom(p)));

        CloudSpec gcp = new CloudSpec(
            "gcp",
            CloudSpec.PROVIDER_GCP,
            GCP_REGION,
            Map.of(
                "bucket", GCP_BUCKET_NAME.getValueFrom(p),
                "serviceAccount", GCP_SERVICE_ACCOUNT.getValueFrom(p)),
            Map.of(
                "domain", GCP_DOMAIN_NAME.getValueFrom(p)));

        // Mirrors SupportedCloudsEnum: internal catalogs per cloud, federated
        // external catalogs proxied via AWS, forbidden buckets as test fixtures,
        // and the dedicated Trino bucket.
        List<CatalogSpec> catalogs = List.of(
            internalCatalog("aws", "aws"),
            internalCatalog("azure", "azure"),
            internalCatalog("gcp", "gcp"),
            new CatalogSpec("azure_external", "azure", CatalogSpec.TYPE_FEDERATED, "aws", null, null),
            new CatalogSpec("gcp_external", "gcp", CatalogSpec.TYPE_FEDERATED, "aws", null, null),
            fixtureCatalog("aws_forbidden", "aws", AWS_S3_BUCKET_NAME_FORBIDDEN.getValueFrom(p)),
            fixtureCatalog("azure_forbidden", "azure", AZURE_BUCKET_FORBIDDEN.getValueFrom(p)),
            fixtureCatalog("gcp_forbidden", "gcp", GCP_BUCKET_NAME_FORBIDDEN.getValueFrom(p)),
            new CatalogSpec(
                "aws_trino", "aws", CatalogSpec.TYPE_INTERNAL, null,
                AWS_S3_BUCKET_NAME_TRINO.getValueFrom(p), null));

        List<EngineSpec> engines = List.of(
            new EngineSpec(EngineSpec.TYPE_SPARK, "aws", 2, null, null),
            new EngineSpec(EngineSpec.TYPE_TRINO, "aws", 2, null, null),
            new EngineSpec(
                EngineSpec.TYPE_SNOWFLAKE, null, null,
                SNOWFLAKE_JDBC_URL.getPropertyName(),
                List.of("aws", "gcp", "azure_external")));

        WorkbenchSpec workbench = new WorkbenchSpec(
            WorkbenchSpec.TYPE_JUPYTER,
            "aws",
            AWS_JUPYTER_DOMAIN.getValueFrom(p),
            new AuthSpec(
                "google-oidc",
                OIDC_EMAIL_DOMAIN.getValueFrom(p),
                OAUTH2_CLIENT_ID.getPropertyName(),
                OAUTH2_CLIENT_SECRET.getPropertyName(),
                OAUTH2_COOKIE_SECRET.getPropertyName()));

        return new AfonDeploymentConfig(
            AfonDeploymentConfig.API_VERSION,
            new DeploymentSpec("emrys-demo", CLUSTER_NAME.getValueFrom(p)),
            List.of(aws, azure, gcp),
            catalogs,
            engines,
            workbench,
            ENV_PROPERTIES_FILE);
    }

    private static CatalogSpec internalCatalog(String name, String cloud) {
        return new CatalogSpec(name, cloud, CatalogSpec.TYPE_INTERNAL, null, null, null);
    }

    private static CatalogSpec fixtureCatalog(String name, String cloud, String bucket) {
        return new CatalogSpec(name, cloud, CatalogSpec.TYPE_INTERNAL, null, bucket, Boolean.TRUE);
    }

    public static void main(String[] args) throws IOException {
        Properties           properties = new AfonPropertiesLoader().getProperties();
        AfonDeploymentConfig config     = export(properties);
        String               yaml       = new DeploymentConfigParser().toYaml(config);
        if (args.length > 0) {
            Files.writeString(Path.of(args[0]), yaml);
        } else {
            System.out.println(yaml);
        }
    }
}
