package health.emrys.afon.config.deployment;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class LegacyConfigExporterTest {

    private final DeploymentConfigParser parser = new DeploymentConfigParser();

    @BeforeAll
    public static void initializeConstants() throws ClassNotFoundException {
        Class.forName(AfonProperties.class.getName());
    }

    @Test
    public void exportedConfigValidatesAndRoundTrips() throws IOException {
        AfonDeploymentConfig config = LegacyConfigExporter.export(syntheticProperties());
        String               yaml   = parser.toYaml(config);

        // Must pass both schema and semantic validation, including checking
        // *Ref fields against the full set of known legacy properties.
        AfonDeploymentConfig reparsed = parser.parse(yaml, Optional.of(AfonProperty.getAllProperties()));
        assertThat(reparsed).isEqualTo(config);
    }

    @Test
    public void exportedConfigMirrorsLegacyTopology() {
        AfonDeploymentConfig config = LegacyConfigExporter.export(syntheticProperties());

        assertThat(config.clouds()).extracting(CloudSpec::provider)
                                   .containsExactly("aws", "azure", "gcp");
        // The nine roles previously conflated in SupportedCloudsEnum:
        assertThat(config.catalogsOrEmpty()).extracting(CatalogSpec::name).containsExactlyInAnyOrder(
            "aws", "azure", "gcp",
            "azure_external", "gcp_external",
            "aws_forbidden", "azure_forbidden", "gcp_forbidden",
            "aws_trino");
        assertThat(config.catalogsOrEmpty()).filteredOn(CatalogSpec::isFederated)
                                            .allMatch(c -> "aws".equals(c.via()));
        assertThat(config.queryEnginesOrEmpty()).extracting(EngineSpec::type)
                                                .containsExactly("spark", "trino", "snowflake");
        assertThat(config.workbench().type()).isEqualTo(WorkbenchSpec.TYPE_JUPYTER);
        assertThat(config.workbench().auth().clientIdRef()).isEqualTo("OAUTH2_CLIENT_ID");
    }

    private static Properties syntheticProperties() {
        Properties p = new Properties();
        p.setProperty("CLUSTER_NAME", "test-cluster");
        p.setProperty("AWS_REGION", "eu-west-2");
        p.setProperty("AWS_S3_BUCKET_NAME", "test-bucket");
        p.setProperty("AWS_S3_ROLE_ARN", "arn:aws:iam::123456789012:role/TestRole");
        p.setProperty("AWS_POLARIS_DOMAIN_NAME", "polaris.example.com");
        p.setProperty("AWS_POLARIS_CERT_ARN", "arn:aws:acm:eu-west-2:123456789012:certificate/test");
        p.setProperty("AWS_POLARIS_DNS_ZONE", "Z1TESTZONE");
        p.setProperty("AWS_S3_BUCKET_NAME_FORBIDDEN", "test-bucket-forbidden");
        p.setProperty("AWS_S3_BUCKET_NAME_TRINO", "test-bucket-trino");
        p.setProperty("AWS_JUPYTER_DOMAIN", "jupyter.example.com");
        p.setProperty("AZURE_REGION", "uksouth");
        p.setProperty("AZURE_STORAGE_ACCOUNT", "teststorageaccount");
        p.setProperty("AZURE_BUCKET", "testcontainer");
        p.setProperty("AZURE_BUCKET_FORBIDDEN", "testcontainer-forbidden");
        p.setProperty("AZURE_DOMAIN_NAME", "polaris-azure.example.com");
        p.setProperty("AZURE_RESOURCE_GROUP", "test_resource_group");
        p.setProperty("AZURE_SUBSCRIPTION", "00000000-0000-0000-0000-000000000000");
        p.setProperty("GCP_BUCKET_NAME", "test_gcp_bucket");
        p.setProperty("GCP_BUCKET_NAME_FORBIDDEN", "test_gcp_bucket_forbidden");
        p.setProperty("GCP_SERVICE_ACCOUNT", "test-sa@test-project.iam.gserviceaccount.com");
        p.setProperty("GCP_DOMAIN_NAME", "polaris-gcp.example.com");
        p.setProperty("OIDC_EMAIL_DOMAIN", "example.com");
        return p;
    }
}
