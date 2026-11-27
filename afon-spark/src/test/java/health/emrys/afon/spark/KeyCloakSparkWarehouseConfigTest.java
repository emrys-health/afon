package health.emrys.afon.spark;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyCloakSparkWarehouseConfigTest {

    private static final String CLIENT_ID     = "a_client";
    private static final String CLIENT_SECRET = "a_secret";

    private final SparkWarehouseConfig config = new KeyCloakSparkWarehouseConfig(properties(),
                                                         AfonUserProvider.createUser(CLIENT_ID, CLIENT_SECRET));

    private static AfonPropertyProvider properties() {
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        properties.setValue(POLARIS_ENDPOINT, "polaris.example.com");
        properties.setValue(OIDC_DOMAIN_NAME, "keycloak.example.com");
        return properties;
    }

    @Test
    void tokenConfigsAuthenticateWithTokenOnly() {
        Map<String, String> configs = config.sparkConfigsWithToken("spark_aws", "aws", "a_token");

        assertThat(configs)
            .containsEntry("spark.sql.catalog.spark_aws.token", "a_token")
            .containsEntry("spark.sql.catalog.spark_aws.warehouse", "aws")
            .containsEntry("spark.sql.catalog.spark_aws.scope", "openid")
            .containsEntry("spark.sql.catalog.spark_aws.oauth2-server-uri",
                           "https://keycloak.example.com/realms/polaris/protocol/openid-connect/token")
            .doesNotContainKey("spark.sql.catalog.spark_aws.credential");
        assertThat(configs.values()).noneMatch(value -> value.contains(CLIENT_SECRET));
    }

    @Test
    void credentialConfigsAuthenticateWithClientCredentials() {
        Map<String, String> configs = config.sparkConfigs("spark_aws", "aws");

        assertThat(configs)
            .containsEntry("spark.sql.catalog.spark_aws.credential", CLIENT_ID + ":" + CLIENT_SECRET)
            .containsEntry("spark.sql.catalog.spark_aws.scope", "openid")
            .doesNotContainKey("spark.sql.catalog.spark_aws.token");
    }

    @TempDir
    Path dir;

    private AfonCatalogs catalogsWithADataProvider() throws IOException {
        Files.writeString(dir.resolve("catalogs.properties"), "");
        Files.writeString(dir.resolve("catalogs-azure.properties"), """
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
            """);
        return new AfonCatalogs(new FilePropertyProvider(dir.toString(), List.of("catalogs")));
    }

    @Test
    void aCatalogOfADataProviderIsReachedAtItsOwnEndpointWithTheUsersToken() throws IOException {
        AfonCatalog dataProvider = catalogsWithADataProvider().bySource(AfonCatalogs.Source.DATA_PROVIDER).get(0);
        Map<String, String> configs = config.sparkConfigsWithToken(dataProvider, "lon_cvd", "a_token");

        assertThat(configs)
            .containsEntry("spark.sql.catalog.lon_cvd", "org.apache.iceberg.spark.SparkCatalog")
            .containsEntry("spark.sql.catalog.lon_cvd.type", "rest")
            .containsEntry("spark.sql.catalog.lon_cvd.uri", "https://azure-polaris.lon.example/api/catalog")
            .containsEntry("spark.sql.catalog.lon_cvd.warehouse", "cvd")
            .containsEntry("spark.sql.catalog.lon_cvd.token", "a_token")
            .containsEntry("spark.sql.catalog.lon_cvd.header.X-Iceberg-Access-Delegation", "vended-credentials")
            // the token is the credential: this config's client, and its Keycloak, have nothing to do with a Data Provider's Polaris
            .doesNotContainKey("spark.sql.catalog.lon_cvd.credential")
            .doesNotContainKey("spark.sql.catalog.lon_cvd.oauth2-server-uri");
        assertThat(configs.values()).noneMatch(value -> value.contains(CLIENT_SECRET) || value.contains("polaris.example.com"));
    }

    @Test
    void aCatalogOfTheStackIsNotOneOfADataProvider() throws IOException {
        AfonCatalog own = catalogsWithADataProvider().all().get(0);
        assertThatThrownBy(() -> config.sparkConfigsWithToken(own, "azure", "a_token")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aConfigForDataProvidersNeedsNoClientNorPolarisOfTheStack() throws IOException {
        AfonCatalog dataProvider = catalogsWithADataProvider().bySource(AfonCatalogs.Source.DATA_PROVIDER).get(0);
        SparkWarehouseConfig forDataProviders = SparkWarehouseConfigFactory.getFactory().createDataProviderConfig(
            new FilePropertyProvider(dir.toString(), List.of("catalogs")));

        Map<String, String> configs = forDataProviders.sparkConfigsWithToken(dataProvider, "test_lon_cvd", "a_token");

        assertThat(configs).containsEntry("spark.sql.catalog.test_lon_cvd.uri", "https://azure-polaris.lon.example/api/catalog")
                           .containsEntry("spark.sql.catalog.test_lon_cvd.warehouse", "cvd").containsEntry("spark.sql.catalog.test_lon_cvd.token", "a_token");
    }
}
