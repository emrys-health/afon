package health.emrys.afon.ops.project;

import health.emrys.afon.cdk.configs.WhitelistConfig;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.project.TreProjectStack;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static health.emrys.afon.ops.project.ProjectStackTemplates.lower;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end, from the project stack file to the domains that its TRE may resolve: the TRE names the catalogs of a Data Provider's project stack, {@code configure} writes them
 * as catalogs of the config that the TRE is deployed with, and the whitelist of that config has the host of each one's endpoint and its storage, with nothing added to
 * {@code whitelist.yaml}.  Every test is run on every cloud, the Data Provider's being on each of them.
 */
class ProjectWhitelistTest {

    @TempDir
    Path home;

    private Path configuredTre(AfonCloud cloud, AfonCloud dataProviderCloud) throws IOException {
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\nAWS_REGION=eu-west-1\n" + ProjectStackEnvAccounts.ACCOUNTS);
        Files.writeString(env.resolve("afon-" + lower(dataProviderCloud) + ".properties"),
                          dataProviderCloud.name() + "_POLARIS_DOMAIN_NAME=" + lower(dataProviderCloud) + "-polaris.dev.click\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        Files.writeString(env.resolve("secret.properties"), "");
        Path data = home.resolve("projects/lon/project.yaml");
        Files.createDirectories(data.getParent());
        Files.writeString(data, ProjectStackTemplates.dataExample(dataProviderCloud).replace("role: data\n", "role: data\n  env: dev\n"));
        String yaml = ProjectStackTemplates.example(cloud)
                                      .replaceAll("(?s)dataProviders:.*?\\nmembers:", "dataProviders:\n  named:\n    - {project-stack: lon, catalogs: [cvd, dbt]}\nmembers:");
        Path file = home.resolve("projects/xyz-yyyy-nnnn/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
        TreProjectStack tre = (TreProjectStack) FileProjectStackProvider.forProjectStack(home, "xyz-yyyy-nnnn").getProjectStack();
        Files.createDirectories(home.resolve("projects/xyz-yyyy-nnnn/secrets"));
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/secrets/tre.secret"), "secret-of-the-client\n");
        return ProjectStackEnv.configure(tre, env, home, file);
    }

    private static String storageOf(AfonCatalog catalog) {
        return switch (catalog.cloud()) {
            case AZURE -> catalog.bucketName() + ".blob.core.windows.net";
            case AWS -> catalog.bucketName() + ".s3.eu-west-1.amazonaws.com";
            case GCP -> "storage.googleapis.com";
        };
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theTreMayResolveTheEndpointAndTheStorageOfTheCatalogsOfItsDataProvider(AfonCloud cloud) throws IOException {
        for (AfonCloud dataProviderCloud : AfonCloud.values()) {
            Path config = configuredTre(cloud, dataProviderCloud);
            FilePropertyProvider properties = new FilePropertyProvider(config.toString());
            List<AfonCatalog> catalogs = new AfonCatalogs(properties).bySource(AfonCatalogs.Source.DATA_PROVIDER);
            assertThat(catalogs).extracting(AfonCatalog::id).containsExactly("lon_cvd", "lon_dbt");

            List<String> domains = new WhitelistConfig(properties, config.toString()).domains(cloud);
            String endpoint = URI.create(catalogs.get(0).catalogUri()).getHost();
            assertThat(endpoint).isEqualTo(lower(dataProviderCloud) + "-polaris.lon.dev.click");
            assertThat(domains).contains(endpoint);
            catalogs.forEach(catalog -> assertThat(domains).contains(storageOf(catalog)));
            // one Polaris: one domain for its endpoint
            assertThat(domains).containsOnlyOnce(endpoint);
            Files.walk(home).sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(home)).forEach(p -> p.toFile().delete());
        }
    }
}
