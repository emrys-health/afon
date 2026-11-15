package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.config.AfonProperty.ENV_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_ISSUER_URL;
import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.project.ProjectFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectFixtures}.
 */
class ProjectEnvTest {

    @TempDir
    Path home;

    /** The example project on a cloud, in a file, with the config of the env it is made from, which has the properties of that cloud. */
    private record OnCloud(AfonProject project, Path projectFile, Path envConfig) {
    }

    private OnCloud on(AfonCloud cloud) throws IOException {
        Path file = ProjectFixtures.writeExample(home.resolve("project-" + lower(cloud) + ".yaml"), cloud);
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\nPROJECT_ID=\nOIDC_ISSUER_URL=https://[OIDC_DOMAIN_NAME]/realms/polaris");
        Files.writeString(env.resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_REGION=a-region\n" + cloud.name() + "_KEYCLOAK_EXTERNAL=false\n");
        Files.writeString(env.resolve("secret.properties"), "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\n");
        return new OnCloud(new FileProjectProvider(file).getProject(), file, env);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheProjectEnvsAfterTheProject(AfonCloud cloud) throws IOException {
        AfonProject project = on(cloud).project();
        assertThat(ProjectEnv.configDir(home, project)).isEqualTo(home.resolve("config-cvd-2026-0007"));
        assertThat(ProjectEnv.cdktfDir(home, project)).isEqualTo(home.resolve("cdktf-cvd-2026-0007"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesTheCommonAndTheCloudProperties(AfonCloud cloud) throws IOException {
        String prefix = cloud.name() + "_";
        String file = "afon-" + lower(cloud) + ".properties";
        var derived = ProjectEnv.derive(on(cloud).project());
        assertThat(derived.keySet()).containsExactly("afon.properties", file);
        assertThat(derived.get("afon.properties")).containsEntry("PROJECT_ID", "cvd-2026-0007")
            .containsEntry("OIDC_AUDIENCE", "cvd-2026-0007-tre").containsEntry("OIDC_GIT_AUDIENCE", "cvd-2026-0007-git")
            .containsEntry("OIDC_PROJECT_CLAIM", "project").containsEntry("OIDC_USER_CLAIM", "polaris_principal")
            .containsEntry("GIT_DOMAIN_NAME", "git.cvd-2026-0007.example.org").containsEntry("TRE_TEARDOWN_TIME", "22:00")
            .containsEntry("TRE_TEARDOWN_TIME_ZONE", "Europe/London");
        assertThat(derived.get(file)).containsEntry(prefix + "KEYCLOAK_EXTERNAL", "true")
            .containsEntry(prefix + "AUDIT_BUCKET", "cvd-2026-0007-audit").containsEntry(prefix + "GITEA_BUCKET", "cvd-2026-0007-gitea")
            .containsEntry(prefix + "JUPYTER_DOMAIN_NAME", "jupyter.cvd-2026-0007.example.org");
        // only what belongs to the project's cloud goes in its file
        assertThat(derived.get(file).keySet()).allMatch(name -> name.startsWith(prefix));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresFromTheEnvWithTheDerivedPropertiesWrittenOverIt(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        Path config = ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());

        assertThat(config).isEqualTo(home.resolve("config-cvd-2026-0007"));
        assertThat(home.resolve("cdktf-cvd-2026-0007")).isDirectory();
        assertThat(config.resolve("secret.properties")).hasContent("KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\n");
        assertThat(config.resolve("project.yaml")).hasSameTextualContentAs(setup.projectFile());
        // the env is left as it was
        assertThat(setup.envConfig().resolve("afon.properties")).content().doesNotContain("Derived");

        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(ENV_NAME)).isEqualTo("dev");
        assertThat(properties.getValue(PROJECT_ID)).isEqualTo("cvd-2026-0007");
        assertThat(properties.getValue(OIDC_ISSUER_URL)).isEqualTo("https://id.example.org/realms/polaris");
        assertThat(properties.getValue(AfonProperty.valueOf(cloud.name() + "_KEYCLOAK_EXTERNAL"))).isEqualTo("true");
        assertThat(properties.getValue(AfonProperty.valueOf(cloud.name() + "_REGION"))).isEqualTo("a-region");
        assertThat(properties.getCloudValue(cloud, AfonCloudProperty.AUDIT_BUCKET)).isEqualTo("cvd-2026-0007-audit");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuringAgainReplacesWhatItMadeAndKeepsNothingStale(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());
        Path config = ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());
        assertThat(Files.readString(config.resolve("afon.properties")).split("Derived from project.yaml", -1)).hasSize(2);
        Files.writeString(setup.envConfig().resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_REGION=another-region\n");
        ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());
        assertThat(new FilePropertyProvider(config.toString()).getValue(AfonProperty.valueOf(cloud.name() + "_REGION"))).isEqualTo("another-region");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesAPropertiesFileTheEnvLacks(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        String file = "afon-" + lower(cloud) + ".properties";
        Files.delete(setup.envConfig().resolve(file));
        Path config = ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());
        assertThat(config.resolve(file)).content().contains(cloud.name() + "_AUDIT_BUCKET=cvd-2026-0007-audit");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesNothingOfGitFromAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        String yaml = ProjectFixtures.example(cloud).replace("    gitBucket: cvd-2026-0007-gitea\n", "")
                                     .replace("  git:\n    domain: git.cvd-2026-0007.example.org\n", "");
        var derived = ProjectEnv.derive(FileProjectProvider.parse(yaml));
        assertThat(derived.get("afon.properties")).doesNotContainKeys("GIT_DOMAIN_NAME", "OIDC_GIT_AUDIENCE").containsKey("OIDC_AUDIENCE");
        assertThat(derived.values().stream().flatMap(m -> m.keySet().stream())).doesNotContain(cloud.name() + "_GITEA_BUCKET")
            .contains(cloud.name() + "_AUDIT_BUCKET");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAValueAShellCouldNotSourceSafely(AfonCloud cloud) {
        AfonProject unsafe = FileProjectProvider.parse(ProjectFixtures.example(cloud).replace("git.cvd-2026-0007.example.org", "x; rm -rf ~"));
        assertThatThrownBy(() -> ProjectEnv.derive(unsafe)).isInstanceOf(ProjectException.class).hasMessageContaining("GIT_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesTheConfigOfTheProjectItselfAsItsEnv(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        Path own = Files.createDirectories(ProjectEnv.configDir(home, setup.project()));
        assertThatThrownBy(() -> ProjectEnv.configure(setup.project(), own, home, setup.projectFile()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is that of the project itself");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheConfigOfAnEnvButNeverAPath(AfonCloud cloud) {
        assertThat(ProjectEnv.envConfigDir(home, "emrys2")).isEqualTo(home.resolve("config-emrys2"));
        assertThatThrownBy(() -> ProjectEnv.envConfigDir(home, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectEnv.envConfigDir(home, "a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAConfigThatIsNotADir(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        assertThatThrownBy(() -> ProjectEnv.configure(setup.project(), home.resolve("missing"), home, setup.projectFile()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
