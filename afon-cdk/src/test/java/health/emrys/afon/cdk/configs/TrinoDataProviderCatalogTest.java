package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.trino.KeyCloakTrinoServerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trino's catalogs for the Data Providers that a TRE project reads: their own endpoint and name, their cloud's storage, and none of the server's credentials.
 */
class TrinoDataProviderCatalogTest {

    @TempDir
    Path dir;

    private AfonCatalogs catalogs() throws IOException {
        Files.writeString(dir.resolve("afon.properties"), """
            AWS_REGION=eu-west-2
            AWS_CATALOG_IDS=aws
            AWS_CATALOG_TYPE_aws=internal
            AZURE_CATALOG_IDS=Lon_cvd
            AZURE_CATALOG_NAME_Lon_cvd=cvd
            AZURE_CATALOG_TYPE_Lon_cvd=rest
            AZURE_CATALOG_SOURCE_Lon_cvd=data-provider
            AZURE_CATALOG_URI_Lon_cvd=https://azure-polaris.lon.example/api/catalog
            AZURE_CATALOG_AUDIENCE_Lon_cvd=provider-lon
            AZURE_BUCKET_NAME_Lon_cvd=afonstorage1
            """);
        return new AfonCatalogs(new FilePropertyProvider(dir.toString(), List.of("afon")));
    }

    private KeyCloakTrinoServerConfiguration trino() throws IOException {
        AfonUser admin = new AfonUser("admin", "pw", "admin@example.org", "admin", "A", "B");
        return new KeyCloakTrinoServerConfiguration(new FilePropertyProvider(dir.toString(), List.of("afon")), "pol-aws.example",
                                                    "https://oidc.example/realms/polaris/protocol/openid-connect/token", admin);
    }

    @Test
    void aDataProviderCatalogReadsItsOwnEndpointAndNameWithTheStorageOfItsCloud() throws IOException {
        AfonCatalog lon = catalogs().bySource(AfonCatalogs.Source.DATA_PROVIDER).get(0);
        String file = trino().dataProviderCatalogProperties(lon, "http://trino-catalog-proxy:8080/lon_cvd");
        assertThat(file).contains("iceberg.rest-catalog.uri=http://trino-catalog-proxy:8080/lon_cvd\n",
                                  "iceberg.rest-catalog.warehouse=cvd\n",
                                  "iceberg.rest-catalog.session=USER",
                                  "fs.azure.enabled=true")
                        .doesNotContain("fs.s3", "pol-aws.example");
    }

    @Test
    void aDataProviderCatalogHoldsNoCredentialOfTheServerNorItsOAuthServer() throws IOException {
        String file = trino().dataProviderCatalogProperties(catalogs().bySource(AfonCatalogs.Source.DATA_PROVIDER).get(0), "http://p/lon_cvd");
        assertThat(file).doesNotContain("oauth2.credential", "oauth2.server-uri", "oauth2.scope", "TRINO_POLARIS_CREDENTIAL", "pw")
                        .contains("iceberg.rest-catalog.oauth2.token=");
    }

    @Test
    void aCatalogOfTheStacksOwnPolarisIsNotADataProviderCatalog() throws IOException {
        assertThatThrownBy(() -> trino().dataProviderCatalogProperties(catalogs().aws(), "http://p/aws")).isInstanceOf(IllegalArgumentException.class)
                                                                                         .hasMessageContaining("not from a Data Provider");
    }

    @Test
    void trinoNamesItInLowerCaseAndTheStacksOwnCatalogsDoNotIncludeIt() throws IOException {
        AfonCatalogs catalogs = catalogs();
        assertThat(TrinoWorkloads.dataProviderCatalogName(catalogs.bySource(AfonCatalogs.Source.DATA_PROVIDER).get(0))).isEqualTo("lon_cvd");
        assertThat(catalogs.all()).extracting(AfonCatalog::id).containsExactly("aws");
    }
}
