package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStackHomeTest {

    @TempDir
    Path afonHome;

    /** The home of the Afon, with an env and the project stack of the example on the cloud. */
    private ProjectStack makeHome(AfonCloud cloud) throws IOException {
        ProjectStack projectStack = FileProjectStackProvider.parse(ProjectStackFixtures.example(cloud));
        Files.createDirectories(afonHome.resolve("config-dev"));
        Files.createDirectories(afonHome.resolve("config-xyz-yyyy-nnnn"));
        Files.createDirectories(afonHome.resolve("cdktf-xyz-yyyy-nnnn"));
        Files.createDirectories(afonHome.resolve("cdktf-dev"));
        Files.createSymbolicLink(afonHome.resolve("config"), afonHome.resolve("config-dev"));
        Files.createSymbolicLink(afonHome.resolve("cdktf"), afonHome.resolve("cdktf-dev"));
        Files.createDirectories(afonHome.resolve("cosign"));
        Files.createDirectories(afonHome.resolve("logs"));
        Files.createDirectories(afonHome.resolve("projects/xyz-yyyy-nnnn"));
        return projectStack;
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void pointsConfigAndCdktfAtTheProjectsAndSharesTheRest(AfonCloud cloud) throws IOException {
        ProjectStack projectStack = makeHome(cloud);
        Path home = ProjectStackHome.prepare(afonHome, projectStack);

        assertThat(home).isEqualTo(afonHome.resolve("projects/xyz-yyyy-nnnn/home"));
        assertThat(home.resolve("config").toRealPath()).isEqualTo(afonHome.resolve("config-xyz-yyyy-nnnn").toRealPath());
        assertThat(home.resolve("cdktf").toRealPath()).isEqualTo(afonHome.resolve("cdktf-xyz-yyyy-nnnn").toRealPath());
        assertThat(home.resolve("cosign").toRealPath()).isEqualTo(afonHome.resolve("cosign").toRealPath());
        assertThat(home.resolve("logs")).exists();
        // not the other envs; the project stacks are shared, so that a command of the home finds its project stack file
        assertThat(home.resolve("config-dev")).doesNotExist();
        assertThat(home.resolve("cdktf-dev")).doesNotExist();
        assertThat(home.resolve("projects/xyz-yyyy-nnnn").toRealPath()).isEqualTo(afonHome.resolve("projects/xyz-yyyy-nnnn").toRealPath());
        // the active env of the deployer is left as it was
        assertThat(afonHome.resolve("config").toRealPath()).isEqualTo(afonHome.resolve("config-dev").toRealPath());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesItAgainWithoutTouchingWhatTheLinksPointTo(AfonCloud cloud) throws IOException {
        ProjectStack projectStack = makeHome(cloud);
        Files.writeString(afonHome.resolve("config-xyz-yyyy-nnnn/afon.properties"), "A=1\n");
        ProjectStackHome.prepare(afonHome, projectStack);
        Path home = ProjectStackHome.prepare(afonHome, projectStack);
        assertThat(home.resolve("config/afon.properties")).hasContent("A=1\n");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAProjectThatIsNotConfigured(AfonCloud cloud) throws IOException {
        ProjectStack projectStack = makeHome(cloud);
        Files.delete(afonHome.resolve("cdktf-xyz-yyyy-nnnn"));
        assertThatThrownBy(() -> ProjectStackHome.prepare(afonHome, projectStack)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("run manage-project-stack configure xyz-yyyy-nnnn");
    }
}
