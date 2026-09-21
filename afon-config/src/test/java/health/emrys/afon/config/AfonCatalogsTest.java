package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AfonCatalogsTest {

    private static AfonPropertyProvider providerWith(Properties properties) {
        return new AfonPropertyProvider() {
            @Override
            public Properties getProperties() {
                return properties;
            }

            @Override
            public Properties getSecretProperties() {
                return new Properties();
            }

            @Override
            public boolean hasProperty(AfonProperty property) {
                return properties.containsKey(property.getName());
            }

            @Override
            public String getValue(AfonProperty property) {
                return properties.getProperty(property.getName());
            }

            @Override
            public String getValue(AfonProperty property, String defaultValue) {
                return properties.getProperty(property.getName(), defaultValue);
            }

            @Override
            public void setValue(AfonProperty property, String value) {
                properties.setProperty(property.getName(), value);
            }
        };
    }

    private static Properties defaultSetProperties() {
        Properties raw = new Properties();
        raw.setProperty("AWS_CATALOG_IDS", "aws,aws_forbidden,aws_trino");
        raw.setProperty("AWS_CATALOG_TYPE_aws", "internal");
        raw.setProperty("AWS_BUCKET_NAME_aws", "aws-bucket");
        raw.setProperty("AWS_CATALOG_TYPE_aws_forbidden", "internal");
        raw.setProperty("AWS_BUCKET_NAME_aws_forbidden", "aws-bucket-forbidden");
        raw.setProperty("AWS_ADMIN_GRANTS_aws_forbidden", "false");
        raw.setProperty("AWS_CATALOG_TYPE_aws_trino", "internal");
        raw.setProperty("AWS_BUCKET_NAME_aws_trino", "aws-bucket-trino");

        raw.setProperty("AZURE_CATALOG_IDS", "azure,azure_external,azure_forbidden");
        raw.setProperty("AZURE_CATALOG_TYPE_azure", "internal");
        raw.setProperty("AZURE_BUCKET_NAME_azure", "azure-bucket");
        raw.setProperty("AZURE_CATALOG_TYPE_azure_external", "external");
        raw.setProperty("AZURE_BUCKET_NAME_azure_external", "azure-bucket-2");
        raw.setProperty("AZURE_CATALOG_TYPE_azure_forbidden", "internal");
        raw.setProperty("AZURE_BUCKET_NAME_azure_forbidden", "azure-bucket-forbidden");
        raw.setProperty("AZURE_ADMIN_GRANTS_azure_forbidden", "false");

        raw.setProperty("GCP_CATALOG_IDS", "gcp,gcp_external,gcp_forbidden");
        raw.setProperty("GCP_CATALOG_TYPE_gcp", "internal");
        raw.setProperty("GCP_BUCKET_NAME_gcp", "gcp-bucket");
        raw.setProperty("GCP_CATALOG_TYPE_gcp_external", "external");
        raw.setProperty("GCP_BUCKET_NAME_gcp_external", "gcp-bucket-2");
        raw.setProperty("GCP_CATALOG_TYPE_gcp_forbidden", "internal");
        raw.setProperty("GCP_BUCKET_NAME_gcp_forbidden", "gcp-bucket-forbidden");
        raw.setProperty("GCP_ADMIN_GRANTS_gcp_forbidden", "false");
        return raw;
    }

    private final AfonCatalogs defaultCatalogs = new AfonCatalogs(providerWith(defaultSetProperties()));

    private static List<String> idsOf(List<AfonCatalog> catalogs) {
        return catalogs.stream().map(AfonCatalog::id).toList();
    }

    @Test
    public void resolvesTheConfiguredCatalogSet() {
        assertThat(idsOf(defaultCatalogs.all()))
                .containsExactlyInAnyOrder(
                        "aws", "aws_forbidden", "aws_trino",
                        "azure", "azure_external", "azure_forbidden",
                        "gcp", "gcp_external", "gcp_forbidden");
    }

    @Test
    public void contributesNoCatalogsForACloudWithNoIdsConfigured() {
        Properties raw = new Properties();
        raw.setProperty("AWS_CATALOG_IDS", "aws");
        raw.setProperty("AWS_CATALOG_TYPE_aws", "internal");
        raw.setProperty("AWS_BUCKET_NAME_aws", "aws-bucket");
        // no AZURE_CATALOG_IDS / GCP_CATALOG_IDS at all - eg a cloud-scoped run

        AfonCatalogs catalogs = new AfonCatalogs(providerWith(raw));

        assertThat(idsOf(catalogs.all())).containsExactly("aws");
    }

    @Test
    public void treatsABlankCatalogIdsListAsNoCatalogs() {
        Properties raw = new Properties();
        raw.setProperty("AWS_CATALOG_IDS", "");

        assertThat(new AfonCatalogs(providerWith(raw)).all()).isEmpty();
    }

    @Test
    public void allIncludingKeepsOnlyTheGivenTypes() {
        assertThat(idsOf(defaultCatalogs.allIncluding("external")))
                .containsExactlyInAnyOrder("azure_external", "gcp_external");
        assertThat(idsOf(defaultCatalogs.allIncluding("external", "internal")))
                .containsExactlyInAnyOrderElementsOf(idsOf(defaultCatalogs.all()));
        assertThat(defaultCatalogs.allIncluding("glue")).isEmpty();
        assertThat(defaultCatalogs.allIncluding()).isEmpty();
    }

    @Test
    public void allExcludingDropsTheGivenTypes() {
        assertThat(idsOf(defaultCatalogs.allExcluding("external")))
                .containsExactlyInAnyOrder(
                        "aws", "aws_forbidden", "aws_trino",
                        "azure", "azure_forbidden",
                        "gcp", "gcp_forbidden");
        assertThat(defaultCatalogs.allExcluding("external", "internal")).isEmpty();
        assertThat(idsOf(defaultCatalogs.allExcluding()))
                .containsExactlyInAnyOrderElementsOf(idsOf(defaultCatalogs.all()));
    }

    @Test
    public void typeFilteringSeparatesCustomTypesFromTheGenericOnes() {
        Properties raw = defaultSetProperties();
        raw.setProperty("AWS_CATALOG_IDS", "aws,aws_glue");
        raw.setProperty("AWS_CATALOG_NAME_aws_glue", "federatedCatalogName");
        raw.setProperty("AWS_CATALOG_TYPE_aws_glue", "glue");
        AfonCatalogs catalogs = new AfonCatalogs(providerWith(raw));

        assertThat(idsOf(catalogs.allIncluding("glue"))).containsExactly("aws_glue");
        assertThat(idsOf(catalogs.allExcluding("glue"))).doesNotContain("aws_glue").contains("aws", "azure", "gcp");
    }

    @Test
    public void getIsCaseInsensitive() {
        assertThat(defaultCatalogs.get("AWS_FORBIDDEN").id()).isEqualTo("aws_forbidden");
        assertThat(defaultCatalogs.get("aws_forbidden").id()).isEqualTo("aws_forbidden");
    }

    @Test
    public void getThrowsForUnknownCatalog() {
        assertThatThrownBy(() -> defaultCatalogs.get("not_a_catalog"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not_a_catalog");
    }

    @Test
    public void catalogNameDefaultsToItsIdButCanDiverge() {
        assertThat(defaultCatalogs.aws().toString()).isEqualTo("aws");

        Properties raw = defaultSetProperties();
        raw.setProperty("AWS_CATALOG_IDS", "aws_glue");
        raw.setProperty("AWS_CATALOG_TYPE_aws_glue", "glue");
        raw.setProperty("AWS_CATALOG_NAME_aws_glue", "my-existing-glue-catalog");
        AfonCatalogs catalogs = new AfonCatalogs(providerWith(raw));

        AfonCatalog glue = catalogs.get("aws_glue");
        assertThat(glue.id()).isEqualTo("aws_glue");
        assertThat(glue.toString()).isEqualTo("my-existing-glue-catalog");
        assertThat(glue.type()).isEqualTo("glue");
    }

    @Test
    public void namedAccessorsResolveIdsCloudsTypesAndBucketNames() {
        assertThat(defaultCatalogs.aws().id()).isEqualTo("aws");
        assertThat(defaultCatalogs.aws().cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(defaultCatalogs.aws().type()).isEqualTo("internal");
        assertThat(defaultCatalogs.aws().bucketName()).isEqualTo("aws-bucket");
        assertThat(defaultCatalogs.aws().adminGrants()).isTrue();

        assertThat(defaultCatalogs.awsForbidden().cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(defaultCatalogs.awsForbidden().bucketName()).isEqualTo("aws-bucket-forbidden");
        assertThat(defaultCatalogs.awsForbidden().adminGrants()).isFalse();

        assertThat(defaultCatalogs.awsTrino().bucketName()).isEqualTo("aws-bucket-trino");

        assertThat(defaultCatalogs.azureExternal().cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(defaultCatalogs.azureExternal().type()).isEqualTo("external");

        assertThat(defaultCatalogs.gcpForbidden().adminGrants()).isFalse();
    }

    @Test
    public void byCloudFiltersToMatchingCloudOnly() {
        assertThat(idsOf(defaultCatalogs.byCloud(AfonCloud.AWS)))
                .containsExactlyInAnyOrder("aws", "aws_forbidden", "aws_trino");
        assertThat(idsOf(defaultCatalogs.byCloud(AfonCloud.AZURE)))
                .containsExactlyInAnyOrder("azure", "azure_external", "azure_forbidden");
        assertThat(idsOf(defaultCatalogs.byCloud(AfonCloud.GCP)))
                .containsExactlyInAnyOrder("gcp", "gcp_external", "gcp_forbidden");
    }

    @Test
    public void baseFiltersToTheCatalogNamedAfterItsCloud() {
        assertThat(idsOf(defaultCatalogs.base()))
                .containsExactlyInAnyOrder("aws", "azure", "gcp");
    }

    @Test
    public void constructingWithMissingCatalogTypeThrows() {
        Properties raw = new Properties();
        raw.setProperty("AWS_CATALOG_IDS", "aws_mystery");
        // no AWS_CATALOG_TYPE_aws_mystery

        assertThatThrownBy(() -> new AfonCatalogs(providerWith(raw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aws_mystery")
                .hasMessageContaining("AWS_CATALOG_TYPE_aws_mystery");
    }

}
