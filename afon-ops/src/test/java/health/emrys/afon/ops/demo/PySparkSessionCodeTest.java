package health.emrys.afon.ops.demo;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PySpark session that a notebook creates has a catalog for each catalog of the stack's own Polaris, and one for each of the Data Providers that a TRE
 * project reads, at its own endpoint, all with the user's token.
 */
class PySparkSessionCodeTest {

    @TempDir
    Path dir;

    private String code(String catalogs) throws IOException {
        Files.writeString(dir.resolve("catalogs.properties"), "");
        Files.writeString(dir.resolve("catalogs-azure.properties"), catalogs);
        FilePropertyProvider properties = new FilePropertyProvider(dir.toString(), List.of("catalogs"));
        properties.setValue(health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT, "polaris.example.com");
        properties.setValue(health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME, "keycloak.example.com");
        AfonCatalogs afonCatalogs = new AfonCatalogs(properties);
        Map<String, String> own = new LinkedHashMap<>();
        afonCatalogs.all().forEach(catalog -> own.put("test_" + catalog, catalog.toString()));
        Map<String, health.emrys.afon.config.AfonCatalog> dataProviders = new LinkedHashMap<>();
        afonCatalogs.bySource(AfonCatalogs.Source.DATA_PROVIDER).forEach(catalog -> dataProviders.put("test_" + catalog.id(), catalog));
        return new PySparkSessionCode(SparkWarehouseConfigFactory.getFactory().createConfig(properties, AfonUserProvider.createUser("id", "secret")))
            .sessionCode(own, dataProviders);
    }

    private static final String OWN = """
        AZURE_CATALOG_IDS=azure
        AZURE_CATALOG_NAME_azure=azure
        AZURE_CATALOG_TYPE_azure=internal
        AZURE_BUCKET_NAME_azure=azure
        """;

    private static final String WITH_A_DATA_PROVIDER = """
        AZURE_CATALOG_IDS=azure,lon_cvd
        AZURE_CATALOG_NAME_azure=azure
        AZURE_CATALOG_TYPE_azure=internal
        AZURE_BUCKET_NAME_azure=azure
        AZURE_CATALOG_NAME_lon_cvd=cvd
        AZURE_CATALOG_TYPE_lon_cvd=rest
        AZURE_CATALOG_SOURCE_lon_cvd=data-provider
        AZURE_CATALOG_URI_lon_cvd=https://azure-polaris.lon.example/api/catalog
        AZURE_CATALOG_AUDIENCE_lon_cvd=provider-lon
        AZURE_BUCKET_NAME_lon_cvd=afonstorage1
        """;

    @Test
    void theSessionHasACatalogForEachDataProviderAtItsOwnEndpointWithTheUsersToken() throws IOException {
        String python = code(WITH_A_DATA_PROVIDER);

        assertThat(python)
            // the stack's own, at its own Polaris
            .contains(".config(\"spark.sql.catalog.test_azure.uri\", \"https://polaris.example.com/api/catalog\")")
            // and the Data Provider's, at its own, with the token of the notebook
            .contains(".config(\"spark.sql.catalog.test_lon_cvd.uri\", \"https://azure-polaris.lon.example/api/catalog\")")
            .contains(".config(\"spark.sql.catalog.test_lon_cvd.warehouse\", \"cvd\")")
            .contains(".config(\"spark.sql.catalog.test_lon_cvd.token\", OIDC_TOKEN)");
        assertThat(python.indexOf("test_lon_cvd")).isLessThan(python.indexOf(").getOrCreate()"));
    }

    @Test
    void theSessionOfAConfigWithNoDataProviderIsWhatItWas() throws IOException {
        String python = code(OWN);
        assertThat(python).contains("spark.sql.catalog.test_azure.uri").doesNotContain("Data Provider").doesNotContain("lon_cvd");
    }

    @Test
    void theSessionSendsTheUsersTokenToSparkConnectAsWellAsToTheCatalogs() throws IOException {
        assertThat(code(OWN)).contains(".remote(f\"sc://spark-connector-0-driver-svc:15002/;x-afon-token={OIDC_TOKEN}\")");
    }
}
