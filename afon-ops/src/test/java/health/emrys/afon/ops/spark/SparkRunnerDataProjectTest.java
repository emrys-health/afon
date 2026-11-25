package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spark tools sign in to a Data Provider's Polaris as its admin client, which a TRE's and a deployment outside a project do not.
 */
class SparkRunnerDataProjectTest {

    @TempDir
    Path dir;

    private AfonPropertyProvider properties(String... lines) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), String.join("\n", lines) + "\n");
        return new FilePropertyProvider(dir.toString(), List.of("afon"));
    }

    private static final AfonUserProvider CONFIGURED_USER = new AfonUserProvider() {
        @Override
        public AfonUser getConfiguredUser(AfonPropertyProvider properties) {
            return AfonUserProvider.createUser("will", "configured");
        }

        @Override
        public boolean isAuthorised(String username, String catalog) {
            return true;
        }

        @Override
        public List<AfonUser> getUsers() {
            return List.of();
        }

        @Override
        public List<health.emrys.afon.config.AfonUserAcl> getUserAcls() {
            return List.of();
        }

        @Override
        public List<String> getUserNames() {
            return List.of();
        }

        @Override
        public AfonUser getUser(String username) {
            return null;
        }
    };

    @Test
    void aProjectWithPolarisAdminClientAndNoProjectClaimIsADataProject() throws IOException {
        assertThat(SparkRunner.isDataProjectStack(properties("PROJECT_ID=lon", "POLARIS_ID=root", "POLARIS_SECRET=s"))).isTrue();
    }

    @Test
    void aTreProjectIsNotOneAsItsTokensNameTheProject() throws IOException {
        assertThat(SparkRunner.isDataProjectStack(properties("PROJECT_ID=cvd-2026", "POLARIS_ID=root", "OIDC_PROJECT_CLAIM=project"))).isFalse();
    }

    @Test
    void aDeploymentOutsideAProjectIsNotOne() throws IOException {
        assertThat(SparkRunner.isDataProjectStack(properties("POLARIS_ID=root"))).isFalse();
    }

    @Test
    void aDataProjectSignsInAsThePolarisAdminClient() throws IOException {
        AfonUser user = SparkRunner.userFor(properties("PROJECT_ID=lon", "POLARIS_ID=root", "POLARIS_SECRET=s3cret"), CONFIGURED_USER);
        assertThat(user.username()).isEqualTo("root");
        assertThat(user.password()).isEqualTo("s3cret");
    }

    @Test
    void elsewhereItSignsInAsTheConfiguredUserAsEver() throws IOException {
        AfonUser user = SparkRunner.userFor(properties("POLARIS_ID=root", "POLARIS_SECRET=s3cret"), CONFIGURED_USER);
        assertThat(user.username()).isEqualTo("will");
    }
}
