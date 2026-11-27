package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.config.AfonCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TRE on AWS reads a Data Provider on Azure, whose catalog is in the config of Azure: a run of the TRE's cloud, as the commands of a project are, loads only AWS's, so the smoke
 * tests read the catalogs of the Data Providers from the config of every cloud.
 */
class SmokeConfigDataProvidersTest {

    @Test
    void readsTheCatalogsOfADataProviderOnAnotherCloudThanTheRunsThatItsCloudFilterWouldHide(@TempDir Path home) throws Exception {
        Assumptions.assumeTrue(System.getenv("AFON_CLOUD") == null && System.getenv("AFON_HOME") == null, "the environment names a cloud or an afon home");
        Path config = Files.createDirectories(home.resolve("config"));
        // the common file of each type is required, the files of the clouds are optional
        Files.writeString(config.resolve("afon.properties"), "X=1\n");
        Files.writeString(config.resolve("secret.properties"), "");
        Files.writeString(config.resolve("catalogs.properties"), "");
        Files.writeString(config.resolve("catalogs-aws.properties"), "AWS_CATALOG_IDS=aws\nAWS_CATALOG_NAME_aws=aws\nAWS_CATALOG_TYPE_aws=internal\n");
        Files.writeString(config.resolve("catalogs-azure.properties"), String.join("\n",
            "AZURE_CATALOG_IDS=azure,lon_cvd",
            "AZURE_CATALOG_NAME_azure=azure",
            "AZURE_CATALOG_TYPE_azure=internal",
            "AZURE_CATALOG_NAME_lon_cvd=cvd",
            "AZURE_CATALOG_TYPE_lon_cvd=rest",
            "AZURE_CATALOG_SOURCE_lon_cvd=data-provider",
            "AZURE_CATALOG_URI_lon_cvd=https://azure-polaris.lon.example/api/catalog",
            "AZURE_CATALOG_AUDIENCE_lon_cvd=provider-lon", ""));
        System.setProperty("afon.home", home.toString());
        System.setProperty("afon.cloud", "aws");
        try {
            List<AfonCatalog> catalogs = SmokeConfig.dataProviderCatalogs();

            assertThat(catalogs).extracting(AfonCatalog::id).containsExactly("lon_cvd");
        } finally {
            System.clearProperty("afon.home");
            System.clearProperty("afon.cloud");
        }
    }
}
