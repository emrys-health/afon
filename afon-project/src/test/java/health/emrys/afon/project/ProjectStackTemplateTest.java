package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectStackFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The templates of the repository, for every role on every cloud: what the deployer completes is a project stack file once the angle brackets are gone.
 */
class ProjectStackTemplateTest {

    /** The templates of the distribution, from the dir of this module. */
    static final Path TEMPLATES = Path.of("..", ProjectStackTemplate.DIR);

    /** The deployer's part: every placeholder replaced, the address by an address. */
    private static String completed(String file) {
        return file.replace("members: []", "members: [ada@example.org]").replace("catalogs: []", "catalogs: [cvd, dbt]").replace("<egress-ip>", "203.0.113.30").replaceAll("<[a-z-]+>", "example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void fillsTheIdCloudEnvAndNodesOfAnIdentityProject(AfonCloud cloud) {
        String file = ProjectStackTemplate.render(TEMPLATES, ProjectStackRole.IDENTITY, cloud, "idp", "dev");
        assertThat(file).doesNotContain("${").contains("id: idp", "cloud: " + lower(cloud), "role: identity", "env: dev",
                                                       "<egress-ip>");
        IdentityProjectStack projectStack = ProjectStackFixtures.identity(completed(file));
        assertThat(projectStack.id().value()).isEqualTo("idp");
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.env()).isEqualTo("dev");
        assertThat(projectStack.egressIp()).isEqualTo("203.0.113.30");
        assertThat(projectStack.nodePool()).isEqualTo(cloud == AfonCloud.AWS ? java.util.Map.of() : cloud == AfonCloud.AZURE
            ? java.util.Map.of("vmSize", "Standard_D2s_v3", "nodeCount", "2") : java.util.Map.of("machineType", "e2-standard-2", "nodeCount", "2"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void fillsTheIdCloudAndDomainPropertyOfATreProject(AfonCloud cloud) {
        String file = ProjectStackTemplate.render(TEMPLATES, ProjectStackRole.TRE, cloud, "xyz-yyyy-nnnn", "dev");
        assertThat(file).doesNotContain("${").contains("id: xyz-yyyy-nnnn", "cloud: " + lower(cloud), "auditBucket: xyz-yyyy-nnnn-audit",
                                                       "# the domains of the TRE are those of the env");
        TreProjectStack projectStack = ProjectStackFixtures.tre(completed(file));
        assertThat(projectStack.id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.identity().projectStack()).isEqualTo("example");
        // its domains are derived from the env's, unless it names them
        assertThat(projectStack.tre().domains()).isEmpty();
        assertThat(projectStack.nodePool()).isEqualTo(cloud == AfonCloud.AWS ? java.util.Map.of() : cloud == AfonCloud.AZURE
            ? java.util.Map.of("vmSize", "Standard_D4s_v6", "nodeCount", "2") : java.util.Map.of("machineType", "e2-standard-4", "nodeCount", "2"));
    }

    @Test
    void isValidYamlAsItIsWritten() throws Exception {
        for (ProjectStackRole role : ProjectStackRole.values()) {
            String template = java.nio.file.Files.readString(TEMPLATES.resolve("project-" + role.value() + ".yaml"));
            Object parsed = new org.yaml.snakeyaml.Yaml().load(template);
            assertThat(parsed instanceof java.util.Map).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void isNotValidUntilTheDeployerHasCompletedIt(AfonCloud cloud) {
        for (ProjectStackRole role : ProjectStackRole.values()) {
            String file = ProjectStackTemplate.render(TEMPLATES, role, cloud, "xyz-yyyy-nnnn", "dev");
            assertThatThrownBy(() -> FileProjectStackProvider.parse(file)).isInstanceOf(ProjectStackException.class);
        }
    }

    @Test
    void leavesTheEnvForTheDeployerIfNoneIsGiven() {
        assertThat(ProjectStackTemplate.render(TEMPLATES, ProjectStackRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null)).contains("env: <env>");
    }

    @Test
    void refusesATemplateThatIsNotThereOrHasAnUnknownVariable() throws Exception {
        Path dir = Files.createTempDirectory("templates");
        assertThatThrownBy(() -> ProjectStackTemplate.render(dir, ProjectStackRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No template");
        Files.writeString(dir.resolve("project-tre.yaml"), "project-stack: {id: ${nope}}\n");
        assertThatThrownBy(() -> ProjectStackTemplate.render(dir, ProjectStackRole.TRE, AfonCloud.AZURE, "xyz-yyyy-nnnn", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("${nope}");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void fillsTheIdCloudEnvAndNodesOfADataProject(AfonCloud cloud) {
        String file = ProjectStackTemplate.render(TEMPLATES, ProjectStackRole.DATA, cloud, "lon", "dev");
        assertThat(file).doesNotContain("${").contains("id: lon", "cloud: " + lower(cloud), "role: data", "env: dev", "<egress-ip>", "catalogs: []");
        DataProjectStack projectStack = ProjectStackFixtures.data(completed(file));
        assertThat(projectStack.id().value()).isEqualTo("lon");
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.env()).isEqualTo("dev");
        assertThat(projectStack.catalogs()).containsExactly("cvd", "dbt");
        // a Data Provider runs Polaris only, so it takes the small nodes of the identity
        assertThat(projectStack.nodePool()).isEqualTo(cloud == AfonCloud.AWS ? java.util.Map.of() : cloud == AfonCloud.AZURE
            ? java.util.Map.of("vmSize", "Standard_D2s_v3", "nodeCount", "2") : java.util.Map.of("machineType", "e2-standard-2", "nodeCount", "2"));
    }
}
