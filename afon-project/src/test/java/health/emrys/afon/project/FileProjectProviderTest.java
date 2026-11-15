package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectFixtures.lower;
import static health.emrys.afon.project.ProjectFixtures.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectFixtures}.
 */
class FileProjectProviderTest {

    private static final String VALID = """
        project: {id: cvd-2026-0007, cloud: ${cloud}}
        identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
        providers:
          - {id: nuh, polarisUri: "https://p.example/api", warehouse: w, audience: provider-nuh, bucket: b}
        stacks:
          create: {auditBucket: audit, gitBucket: gitea}
          git: {domain: git.example.org}
          tre: {domains: {}}
        """;

    private static String valid(AfonCloud cloud) {
        return render(VALID, cloud);
    }

    private static ProjectException invalid(String yaml) {
        try {
            FileProjectProvider.parse(yaml);
        } catch (ProjectException e) {
            return e;
        }
        throw new AssertionError("expected the project to be invalid");
    }

    private static AfonCloud anotherThan(AfonCloud cloud) {
        return AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void loadsTheExampleFile(AfonCloud cloud) {
        AfonProject project = FileProjectProvider.parse(ProjectFixtures.example(cloud));
        assertThat(project.id().value()).isEqualTo("cvd-2026-0007");
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.identity().treClient()).isEqualTo("cvd-2026-0007-tre");
        assertThat(project.identity().gitClient()).isEqualTo("cvd-2026-0007-git");
        assertThat(project.providers()).extracting(AfonProject.Provider::cloud).containsExactly(cloud, cloud);
        assertThat(project.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(project.tre().domains()).containsEntry(cloud.name() + "_JUPYTER_DOMAIN_NAME", "jupyter.cvd-2026-0007.example.org");
        assertThat(project.tre().teardown()).isEqualTo("22:00");
        assertThat(project.tre().timeZone()).isEqualTo("Europe/London");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheCloudOfAProvidersStorageFromItselfNotFromTheProject(AfonCloud cloud) {
        AfonCloud other = anotherThan(cloud);
        AfonProject project = FileProjectProvider.parse(valid(cloud).replace("bucket: b}", "bucket: b, cloud: " + lower(other) + "}"));
        assertThat(project.providers()).extracting(AfonProject.Provider::cloud).containsExactly(other);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void defaultsTheTreStackToTheFullOneOfTheProjectsCloud(AfonCloud cloud) {
        AfonProject project = FileProjectProvider.parse(valid(cloud));
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(project.tre().teardown()).isNull();
        assertThat(project.identity().treClient()).isEqualTo("cvd-2026-0007-tre");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesADomainOfTheProjectsCloudAndRejectsOneThatIsNotAKnownProperty(AfonCloud cloud) {
        String prefix = cloud.name() + "_";
        AfonProject project = FileProjectProvider.parse(valid(cloud).replace("tre: {domains: {}}",
                                                                               "tre: {domains: {" + prefix + "JUPYTER_DOMAIN_NAME: j.example.org}}"));
        assertThat(project.tre().domains()).containsEntry(prefix + "JUPYTER_DOMAIN_NAME", "j.example.org");
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {domains: {" + prefix + "JUPYTR_DOMAIN_NAME: j}}")).problems())
            .contains("stacks.tre.domains." + prefix + "JUPYTR_DOMAIN_NAME is not a known property");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reportsEveryProblemAtOnce(AfonCloud cloud) {
        ProjectException e = invalid(valid(cloud).replace("cvd-2026-0007", "CVD_1").replace("audience: provider-nuh, ", ""));
        assertThat(e.problems()).anyMatch(p -> p.startsWith("project.id")).anyMatch(p -> p.equals("providers[0].audience is required"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnUnknownKey(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("git: {domain: git.example.org}", "git: {domian: git.example.org}")).problems())
            .anyMatch(p -> p.startsWith("stacks.git.domian is not a known key"))
            .anyMatch(p -> p.equals("stacks.git.domain is required"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsDuplicateProviderIds(AfonCloud cloud) {
        String twice = valid(cloud).replace("bucket: b}", "bucket: b}\n  - {id: nuh, polarisUri: \"https://q\", warehouse: w, audience: a, bucket: c}");
        assertThat(invalid(twice).problems()).contains("providers[1].id 'nuh' is used by another provider");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsATreStackOfAnotherCloud(AfonCloud cloud) {
        String stack = lower(anotherThan(cloud)) + "-full";
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {stack: " + stack + "}")).problems())
            .anyMatch(p -> p.contains("is not on the project's cloud"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnInsecureIssuer(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("https://id", "http://id")).problems()).anyMatch(p -> p.contains("https URL"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void findsAProjectByItsIdInItsOwnDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectFixtures.writeExample(home.resolve("projects/cvd-2026-0007/project.yaml"), cloud);
        FileProjectProvider provider = FileProjectProvider.forProject(home, "cvd-2026-0007");
        assertThat(provider.file()).isEqualTo(home.resolve("projects/cvd-2026-0007/project.yaml"));
        assertThat(provider.exists()).isTrue();
        assertThat(provider.getProject().id().value()).isEqualTo("cvd-2026-0007");
        assertThat(FileProjectProvider.keysDir(home, "cvd-2026-0007")).isEqualTo(home.resolve("projects/cvd-2026-0007/keys"));
        assertThat(FileProjectProvider.forProject(home, "other-project").exists()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdThatWouldNamePathsOutsideTheProjectsDir(AfonCloud cloud, @TempDir Path home) {
        assertThatThrownBy(() -> FileProjectProvider.forProject(home, "../config")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileProjectProvider.projectFile(home, "a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAFileWhoseIdIsNotThatOfItsDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectFixtures.writeExample(home.resolve("projects/other-project/project.yaml"), cloud);
        assertThatThrownBy(() -> FileProjectProvider.forProject(home, "other-project").getProject())
            .isInstanceOf(ProjectException.class).hasMessageContaining("is not 'other-project'");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void hasNoGitOfItsOwnWithoutStacksGit(AfonCloud cloud) {
        AfonProject project = FileProjectProvider.parse(valid(cloud).replace("create: {auditBucket: audit, gitBucket: gitea}", "create: {auditBucket: audit}")
                                                                    .replace("  git: {domain: git.example.org}\n", ""));
        assertThat(project.git()).isNull();
        assertThat(project.identity().gitClient()).isNull();
        assertThat(project.create().gitBucket()).isNull();
        assertThat(ProjectPlan.of(project).stages()).extracting(ProjectPlan.Stage::id)
            .containsExactly(ProjectPlan.StageId.CREATE, ProjectPlan.StageId.IDENTITY, ProjectPlan.StageId.TRE);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAGitClientForAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        String noGit = valid(cloud).replace("  git: {domain: git.example.org}\n", "")
                                   .replace("identity: {issuerUrl:", "identity: {clients: {git: other-git}, issuerUrl:");
        assertThat(invalid(noGit).problems()).anyMatch(p -> p.startsWith("identity.clients.git is set but there is no stacks.git"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheEnvWhoseConfigItIsMadeFrom(AfonCloud cloud) {
        assertThat(FileProjectProvider.parse(valid(cloud)).env()).isNull();
        assertThat(FileProjectProvider.parse(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: emrys2}")).env())
            .isEqualTo("emrys2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnEnvThatIsNotANameAsItWouldNameAPath(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: ../secret}")).problems())
            .contains("project.env '../secret' is not the name of an env, eg dev for config-dev");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesWhereItDeploysFromItsEnvNotFromItsOwnFile(AfonCloud cloud) {
        // the region and the account are those of the config it is made from, so naming them in the file is a mistake, not a second source
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", region: a-region, account: acct}")).problems())
            .anyMatch(p -> p.startsWith("project.region is not a known key"))
            .anyMatch(p -> p.startsWith("project.account is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsInvalidYaml(AfonCloud cloud) {
        assertThatThrownBy(() -> FileProjectProvider.parse("project: [")).isInstanceOf(ProjectException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheStacksInOrderWithStatePerProject(AfonCloud cloud) {
        String c = lower(cloud);
        ProjectPlan plan = ProjectPlan.of(FileProjectProvider.parse(ProjectFixtures.example(cloud)));
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::id).containsExactly(
            ProjectPlan.StageId.CREATE, ProjectPlan.StageId.IDENTITY, ProjectPlan.StageId.GIT, ProjectPlan.StageId.TRE);
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::stateKey).containsExactly(
            "cvd-2026-0007/" + c + "-create", null, "cvd-2026-0007/" + c + "-git", "cvd-2026-0007/" + c + "-full");
        assertThat(plan.nightlyStages()).extracting(ProjectPlan.Stage::id).containsExactly(ProjectPlan.StageId.TRE);
    }
}
