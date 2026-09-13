package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AfonCatalogsTest {

    static class ConfiguredConstants {
        public static final String CATALOG_NAMES_CSV = "aws, gcp_forbidden ,Azure_External";
    }

    static class UnparseableConstants {
        public static final String CATALOG_NAMES_CSV = "not_a_cloud";
    }

    private final AfonCatalogs defaultCatalogs = new AfonCatalogs(new ConstantsPropertyProvider());
    private final AfonCatalogs configuredCatalogs = new AfonCatalogs(new ConstantsPropertyProvider(ConfiguredConstants.class));

    private static List<String> namesOf(List<AfonCatalog> catalogs) {
        return catalogs.stream().map(AfonCatalog::toString).toList();
    }

    @Test
    public void defaultsToFullBuiltInCatalogSetWhenPropertyAbsent() {
        assertThat(namesOf(defaultCatalogs.all()))
                .containsExactly(AfonCatalogs.DEFAULT_CATALOG_NAMES_CSV.split(","));
    }

    @Test
    public void defaultsToFullBuiltInCatalogSetWhenPropertyBlank() {
        AfonPropertyProvider blank = new ConstantsPropertyProvider();
        blank.setValue(AfonProperty.CATALOG_NAMES_CSV, "");
        assertThat(namesOf(new AfonCatalogs(blank).all()))
                .containsExactly(AfonCatalogs.DEFAULT_CATALOG_NAMES_CSV.split(","));
    }

    @Test
    public void restrictsToConfiguredCatalogsWhenPropertySet() {
        assertThat(namesOf(configuredCatalogs.all()))
                .containsExactly("aws", "gcp_forbidden", "azure_external");
    }

    @Test
    public void getIsCaseInsensitive() {
        assertThat(defaultCatalogs.get("AWS_FORBIDDEN").toString()).isEqualTo("aws_forbidden");
        assertThat(defaultCatalogs.get("aws_forbidden").toString()).isEqualTo("aws_forbidden");
    }

    @Test
    public void getTrimsWhitespaceAndNormalizesCaseFromConfiguredNames() {
        assertThat(configuredCatalogs.get("azure_external").toString()).isEqualTo("azure_external");
        assertThat(configuredCatalogs.get("GCP_FORBIDDEN").toString()).isEqualTo("gcp_forbidden");
    }

    @Test
    public void getThrowsForUnknownCatalog() {
        assertThatThrownBy(() -> defaultCatalogs.get("not_a_catalog"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not_a_catalog");
    }

    @Test
    public void getThrowsForCatalogExcludedByConfiguredSubset() {
        assertThatThrownBy(() -> configuredCatalogs.get("gcp"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    public void namedAccessorsResolveCanonicalNamesCloudsAndVariants() {
        assertThat(defaultCatalogs.aws().toString()).isEqualTo("aws");
        assertThat(defaultCatalogs.aws().name()).isEqualTo("AWS");
        assertThat(defaultCatalogs.aws().cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(defaultCatalogs.aws().variant()).isEqualTo(AfonCatalog.Variant.BASE);

        assertThat(defaultCatalogs.awsForbidden().toString()).isEqualTo("aws_forbidden");
        assertThat(defaultCatalogs.awsForbidden().cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(defaultCatalogs.awsForbidden().variant()).isEqualTo(AfonCatalog.Variant.FORBIDDEN);

        assertThat(defaultCatalogs.awsTrino().toString()).isEqualTo("aws_trino");
        assertThat(defaultCatalogs.awsTrino().cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(defaultCatalogs.awsTrino().variant()).isEqualTo(AfonCatalog.Variant.TRINO);

        assertThat(defaultCatalogs.azure().cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(defaultCatalogs.azure().variant()).isEqualTo(AfonCatalog.Variant.BASE);

        assertThat(defaultCatalogs.azureExternal().toString()).isEqualTo("azure_external");
        assertThat(defaultCatalogs.azureExternal().cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(defaultCatalogs.azureExternal().variant()).isEqualTo(AfonCatalog.Variant.EXTERNAL);

        assertThat(defaultCatalogs.azureForbidden().toString()).isEqualTo("azure_forbidden");
        assertThat(defaultCatalogs.azureForbidden().variant()).isEqualTo(AfonCatalog.Variant.FORBIDDEN);

        assertThat(defaultCatalogs.gcp().cloud()).isEqualTo(AfonCloud.GCP);
        assertThat(defaultCatalogs.gcp().variant()).isEqualTo(AfonCatalog.Variant.BASE);

        assertThat(defaultCatalogs.gcpExternal().toString()).isEqualTo("gcp_external");
        assertThat(defaultCatalogs.gcpExternal().variant()).isEqualTo(AfonCatalog.Variant.EXTERNAL);

        assertThat(defaultCatalogs.gcpForbidden().toString()).isEqualTo("gcp_forbidden");
        assertThat(defaultCatalogs.gcpForbidden().variant()).isEqualTo(AfonCatalog.Variant.FORBIDDEN);
    }

    @Test
    public void byCloudFiltersToMatchingCloudOnly() {
        assertThat(namesOf(defaultCatalogs.byCloud(AfonCloud.AWS)))
                .containsExactlyInAnyOrder("aws", "aws_forbidden", "aws_trino");
        assertThat(namesOf(defaultCatalogs.byCloud(AfonCloud.AZURE)))
                .containsExactlyInAnyOrder("azure", "azure_external", "azure_forbidden");
        assertThat(namesOf(defaultCatalogs.byCloud(AfonCloud.GCP)))
                .containsExactlyInAnyOrder("gcp", "gcp_external", "gcp_forbidden");
    }

    @Test
    public void baseFiltersToOnlyBaseVariant() {
        assertThat(namesOf(defaultCatalogs.base()))
                .containsExactlyInAnyOrder("aws", "azure", "gcp");
    }

    @Test
    public void constructingWithUnparseableCatalogNameThrows() {
        assertThatThrownBy(() -> new AfonCatalogs(new ConstantsPropertyProvider(UnparseableConstants.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_a_cloud");
    }

}
