package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The templates of the repository, for every role on every cloud: what the deployer completes is a project file once the angle brackets are gone.
 */
class ProjectTemplateTest {

    /** The templates of the distribution, from the dir of this module. */
    static final Path TEMPLATES = Path.of("..", ProjectTemplate.DIR);

    /** The deployer's part: every placeholder replaced, the address by an address. */
    private static String completed(String file) {
        return file.replace("members: []", "members: [ada@example.org]").replace("<egress-ip>", "203.0.113.30").replaceAll("<[a-z-]+>", "example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void fillsTheIdCloudEnvAndNodesOfAnIdentityProject(AfonCloud cloud) {
        String file = ProjectTemplate.render(TEMPLATES, ProjectRole.IDENTITY, cloud, "idp", "dev");
        assertThat(file).doesNotContain("${").contains("id: idp", "cloud: " + lower(cloud), "role: identity", "env: dev",
                                                       "<egress-ip>");
        IdentityProject project = ProjectFixtures.identity(completed(file));
        assertThat(project.id().value()).isEqualTo("idp");
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.env()).isEqualTo("dev");
        assertThat(project.egressIp()).isEqualTo("203.0.113.30");
        assertThat(project.nodePool()).isEqualTo(cloud == AfonCloud.AWS ? java.util.Map.of() : cloud == AfonCloud.AZURE
            ? java.util.Map.of("vmSize", "Standard_D2s_v3", "nodeCount", "2") : java.util.Map.of("machineType", "e2-standard-2", "nodeCount", "2"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void fillsTheIdCloudAndDomainPropertyOfATreProject(AfonCloud cloud) {
        String file = ProjectTemplate.render(TEMPLATES, ProjectRole.TRE, cloud, "xyz-yyyy-nnnn", "dev");
        assertThat(file).doesNotContain("${").contains("id: xyz-yyyy-nnnn", "cloud: " + lower(cloud), "auditBucket: xyz-yyyy-nnnn-audit",
                                                       "# the domains of the TRE are those of the env");
        TreProject project = ProjectFixtures.tre(completed(file));
        assertThat(project.id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.identity().project()).isEqualTo("example");
        // its domains are derived from the env's, unless it names them
        assertThat(project.tre().domains()).isEmpty();
        assertThat(project.nodePool()).isEqualTo(cloud == AfonCloud.AWS ? java.util.Map.of() : cloud == AfonCloud.AZURE
            ? java.util.Map.of("vmSize", "Standard_D4s_v6", "nodeCount", "2") : java.util.Map.of("machineType", "e2-standard-4", "nodeCount", "2"));
    }

    @Test
    void isValidYamlAsItIsWritten() throws Exception {
        for (ProjectRole role : ProjectRole.values()) {
            String template = java.nio.file.Files.readString(TEMPLATES.resolve("project-" + role.value() + ".yaml"));
            Object parsed = new org.yaml.snakeyaml.Yaml().load(template);
            assertThat(parsed instanceof java.util.Map).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void isNotValidUntilTheDeployerHasCompletedIt(AfonCloud cloud) {
        for (ProjectRole role : ProjectRole.values()) {
            String file = ProjectTemplate.render(TEMPLATES, role, cloud, "xyz-yyyy-nnnn", "dev");
            assertThatThrownBy(() -> FileProjectProvider.parse(file)).isInstanceOf(ProjectException.class);
        }
    }

    @Test
    void leavesTheEnvForTheDeployerIfNoneIsGiven() {
        assertThat(ProjectTemplate.render(TEMPLATES, ProjectRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null)).contains("env: <env>");
    }

    @Test
    void refusesATemplateThatIsNotThereOrHasAnUnknownVariable() throws Exception {
        Path dir = Files.createTempDirectory("templates");
        assertThatThrownBy(() -> ProjectTemplate.render(dir, ProjectRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No template");
        Files.writeString(dir.resolve("project-tre.yaml"), "project: {id: ${nope}}\n");
        assertThatThrownBy(() -> ProjectTemplate.render(dir, ProjectRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("${nope}");
    }
}
