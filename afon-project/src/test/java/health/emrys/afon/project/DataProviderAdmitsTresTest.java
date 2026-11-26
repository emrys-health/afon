package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectStackFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Data Provider's Polaris admits the egress IPs of the TRE project stacks that name its catalogs, in {@code <CLOUD>_POLARIS_ALLOWED_CIDRS}, when it is configured.
 * Every test is run on every cloud.
 */
class DataProviderAdmitsTresTest {

    private static final String ACCOUNTS = "AZURE_SUBSCRIPTION=sub-1\nAWS_ACCOUNT_ID=111111111111\nGCP_PROJECT_ID=gcp-proj\n";
    /** The egress IP that the TRE of the fixtures leaves from. */
    private static final String TRE_EGRESS = "203.0.113.10";

    @TempDir
    Path home;

    private Path env(AfonCloud cloud, String extra) throws IOException {
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        Files.writeString(env.resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_POLARIS_DOMAIN_NAME=" + lower(cloud) + "-polaris.dev.click\n" + extra);
        Files.writeString(env.resolve("catalogs.properties"), "");
        Files.writeString(env.resolve("catalogs-" + lower(cloud) + ".properties"), cloud.name() + "_CATALOG_IDS=" + lower(cloud) + "\n"
            + cloud.name() + "_CATALOG_NAME_" + lower(cloud) + "=" + lower(cloud) + "\n" + cloud.name() + "_CATALOG_TYPE_" + lower(cloud) + "=internal\n");
        return env;
    }

    private Path dataFile(AfonCloud cloud) throws IOException {
        Path file = home.resolve("projects/lon/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ProjectStackFixtures.dataExample(cloud).replace("role: data\n", "role: data\n  env: dev\n"));
        return file;
    }

    /** A TRE project stack of the given id, whose {@code dataProviders} are the lines given. */
    private void tre(AfonCloud cloud, String id, String egressLine, String providers) throws IOException {
        String yaml = ProjectStackFixtures.example(cloud).replace("xyz-yyyy-nnnn", id).replace("  egressIp: 203.0.113.10\n", egressLine)
                                     .replaceAll("(?s)dataProviders:.*?\\nmembers:", "dataProviders:\n" + providers + "\nmembers:")
                                     .replace("  cloud: " + lower(cloud) + "\n", "  cloud: " + lower(cloud) + "\n  env: dev\n");
        Path file = home.resolve("projects/" + id + "/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private static final String NAMES_LON = "  named:\n    - {projectStack: lon, catalogs: [cvd]}";

    private AfonPropertyProvider configureLon(AfonCloud cloud, String envExtra) throws IOException {
        Path file = dataFile(cloud);
        Path env = env(cloud, envExtra);
        ProjectStackFixtures.writeSecrets(home);
        return new FilePropertyProvider(ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file).toString());
    }

    private static String allowed(AfonPropertyProvider properties, AfonCloud cloud) {
        return properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_ALLOWED_CIDRS, "");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void admitsTheEgressIpOfTheTreThatNamesIt(AfonCloud cloud) throws IOException {
        env(cloud, "");
        tre(cloud, "xyz-yyyy-nnnn", "  egressIp: " + TRE_EGRESS + "\n", NAMES_LON);
        assertThat(allowed(configureLon(cloud, ""), cloud)).isEqualTo(TRE_EGRESS + "/32");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void keepsWhatTheConfigAllowsAndAdmitsEachTreOnce(AfonCloud cloud) throws IOException {
        env(cloud, "");
        tre(cloud, "xyz-yyyy-nnnn", "  egressIp: " + TRE_EGRESS + "\n", NAMES_LON);
        tre(cloud, "abc-yyyy-nnnn", "  egressIp: " + TRE_EGRESS + "\n", NAMES_LON);
        tre(cloud, "def-yyyy-nnnn", "  egressIp: 203.0.113.11\n", NAMES_LON);
        assertThat(allowed(configureLon(cloud, cloud.name() + "_POLARIS_ALLOWED_CIDRS=198.51.100.0/24\n"), cloud))
            .isEqualTo("198.51.100.0/24," + TRE_EGRESS + "/32,203.0.113.11/32");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheEgressIpOfTheConfigWhenTheTreNamesNone(AfonCloud cloud) throws IOException {
        env(cloud, cloud.name() + "_EGRESS_IP=203.0.113.99\n");
        tre(cloud, "xyz-yyyy-nnnn", "", NAMES_LON);
        assertThat(allowed(configureLon(cloud, cloud.name() + "_EGRESS_IP=203.0.113.99\n"), cloud)).isEqualTo("203.0.113.99/32");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheAllowListAsTheConfigHasItWhenNoTreNamesIt(AfonCloud cloud) throws IOException {
        // a TRE that names another Data Provider, or one defined in full, is not admitted
        env(cloud, "");
        tre(cloud, "xyz-yyyy-nnnn", "  egressIp: " + TRE_EGRESS + "\n",
            "  defined:\n    - {id: x, catalogUri: 'https://x.example.org/api/catalog', catalogName: c, audience: provider-x, bucket: b}");
        assertThat(allowed(configureLon(cloud, ""), cloud)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToConfigureWhenATreThatReadsItHasNoKnownAddress(AfonCloud cloud) throws IOException {
        env(cloud, "");
        tre(cloud, "xyz-yyyy-nnnn", "", NAMES_LON);
        Path file = dataFile(cloud);
        Path env = env(cloud, "");
        ProjectStackFixtures.writeSecrets(home);
        assertThatThrownBy(() -> ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file))
            .isInstanceOf(ProjectStackException.class)
            .hasMessageContaining("xyz-yyyy-nnnn reads this Data Provider")
            .hasMessageContaining("projectStack.egressIp");
    }
}
