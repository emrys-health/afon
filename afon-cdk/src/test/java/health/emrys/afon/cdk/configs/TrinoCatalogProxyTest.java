package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proxy between Trino and the Data Providers' catalogs answers Trino's call to configure a catalog itself, and passes the rest on to the Data Provider.
 */
class TrinoCatalogProxyTest {

    @TempDir
    Path dir;

    private List<AfonCatalog> catalogs(String uri, String name) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), """
            AZURE_CATALOG_IDS=lon_cvd
            AZURE_CATALOG_NAME_lon_cvd=%s
            AZURE_CATALOG_TYPE_lon_cvd=rest
            AZURE_CATALOG_SOURCE_lon_cvd=data-provider
            AZURE_CATALOG_URI_lon_cvd=%s
            AZURE_CATALOG_AUDIENCE_lon_cvd=provider-lon
            AZURE_BUCKET_NAME_lon_cvd=afonstorage1
            """.formatted(name, uri));
        return new AfonCatalogs(new FilePropertyProvider(dir.toString(), List.of("afon"))).bySource(AfonCatalogs.Source.DATA_PROVIDER);
    }

    @Test
    void answersTheCallToConfigureTheCatalogItselfWithItsPrefix() throws IOException {
        String conf = TrinoCatalogProxy.nginxConf(catalogs("https://azure-polaris.lon.example/api/catalog", "cvd"));
        assertThat(conf).contains("location = /lon_cvd/v1/config {", "return 200 '{\"defaults\":{},\"overrides\":{\"prefix\":\"cvd\"}}';");
    }

    @Test
    void passesTheRestOnToTheDataProviderWithItsHostAndVerifiesIt() throws IOException {
        String conf = TrinoCatalogProxy.nginxConf(catalogs("https://azure-polaris.lon.example/api/catalog/", "cvd"));
        assertThat(conf).contains("location /lon_cvd/ {",
                                  "proxy_pass https://azure-polaris.lon.example/api/catalog/;",
                                  "proxy_set_header Host azure-polaris.lon.example;",
                                  "proxy_ssl_server_name on;",
                                  "proxy_ssl_verify on;",
                                  "proxy_ssl_verify_depth 5;")
                        .doesNotContain("Authorization");
    }

    @Test
    void keepsThePortOfTheEndpoint() throws IOException {
        assertThat(TrinoCatalogProxy.nginxConf(catalogs("https://polaris.example:8443/api/catalog", "cvd")))
            .contains("proxy_pass https://polaris.example:8443/api/catalog/;", "proxy_set_header Host polaris.example;");
    }

    @Test
    void refusesAnEndpointThatIsNotHttps() throws IOException {
        assertThatThrownBy(() -> TrinoCatalogProxy.nginxConf(catalogs("http://polaris.example/api/catalog", "cvd")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not an https URL");
    }

    @Test
    void refusesANameThatCouldChangeTheConfiguration() throws IOException {
        assertThatThrownBy(() -> TrinoCatalogProxy.nginxConf(catalogs("https://polaris.example/api/catalog", "cvd';}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the proxy's configuration");
    }

    @Test
    void trinoReachesACatalogAtItsNameOnTheProxysService() {
        assertThat(TrinoCatalogProxy.uriOf("lon_cvd")).isEqualTo("http://trino-catalog-proxy:8080/lon_cvd");
    }
}
