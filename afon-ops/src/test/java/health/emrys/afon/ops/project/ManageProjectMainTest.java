package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.ops.project.ProjectTemplates.lower;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test is run on every cloud, see {@link ProjectTemplates}.
 */
class ManageProjectMainTest {

    private static final String PROJECT = "cvd-2026-0007";

    @TempDir
    Path home;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private ManageProjectMain command;
    /** The commands that were run, each as its args after the launcher. */
    private final List<List<String>> ran = new ArrayList<>();
    private int status;
    private boolean gitStackDefined;
    private boolean treDeploysGit = true;

    /** Writes the project of the example on the cloud, and makes the command that acts on it. */
    private void setUp(AfonCloud cloud) throws Exception {
        ProjectTemplates.write(home, PROJECT, cloud);
        ManageProjectMain.Executor executor = argv -> {
            ran.add(argv.subList(1, argv.size()));
            // only the identity stage can be made to fail
            return argv.contains("manage-identity") ? status : 0;
        };
        command = new ManageProjectMain(new PrintStream(out), new PrintStream(err), home, executor, () -> "/cli",
                                        new ProjectCommands.Stacks(stack -> gitStackDefined || !stack.type().equals("git"), stack -> treDeploysGit));
    }

    private void configure() throws Exception {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n");
        assertThat(command.run(new String[]{"configure", PROJECT, "--env", "dev"})).isZero();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void validatesAProjectFile(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"validate", PROJECT})).isZero();
        assertThat(out.toString()).contains("is a valid project: cvd-2026-0007 on " + lower(cloud) + ", made from the active config, with 2 provider(s)");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheStagesInOrderWithTheirState(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"plan", PROJECT})).isZero();
        assertThat(out.toString()).contains(
            "1. create (" + c + "-create), state cvd-2026-0007/" + c + "-create",
            "2. identity",
            "3. git (" + c + "-git), state cvd-2026-0007/" + c + "-git",
            "4. tre (" + c + "-full), state cvd-2026-0007/" + c + "-full, deleted and set up again nightly");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsOnAnInvalidFileListingItsProblems(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.createDirectories(home.resolve("projects/bad-project"));
        Files.writeString(home.resolve("projects/bad-project/project.yaml"), "project: {id: Bad_Id}\n");
        assertThat(command.run(new String[]{"validate", "bad-project"})).isEqualTo(1);
        assertThat(err.toString()).contains("is not a valid project", "project.id", "project.cloud is required");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWhenTheFileIsNotOfTheProjectItsDirIsNamedFor(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.createDirectories(home.resolve("projects/other-project"));
        ProjectTemplates.write(home, "other-project", cloud);
        assertThat(command.run(new String[]{"validate", "other-project"})).isEqualTo(1);
        assertThat(err.toString()).contains("project.id 'cvd-2026-0007' is not 'other-project', the name of the dir that holds it");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheConfigAndCdktfEnvsOfTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n");
        assertThat(command.run(new String[]{"configure", PROJECT, "--env", "dev"})).isZero();
        assertThat(home.resolve("config-cvd-2026-0007/afon.properties")).content().contains("ENV_NAME=dev", "PROJECT_ID=cvd-2026-0007");
        assertThat(home.resolve("cdktf-cvd-2026-0007")).isDirectory();
        assertThat(out.toString()).contains("Configured project cvd-2026-0007", "Not configured yet");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresFromTheEnvTheProjectNamesNotTheActiveOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        Files.createDirectories(home.resolve("config-reserved"));
        Files.writeString(home.resolve("config-reserved/afon.properties"), "ENV_NAME=reserved\n");
        Files.createDirectories(home.resolve("config-mine"));
        Files.writeString(home.resolve("config-mine/afon.properties"), "ENV_NAME=mine\n");
        // the active config is another env's, which the project must not be made from
        Files.createSymbolicLink(home.resolve("config"), home.resolve("config-reserved"));
        Files.writeString(home.resolve("projects/cvd-2026-0007/project.yaml"),
                          ProjectTemplates.example(cloud).replace("cloud: " + c, "cloud: " + c + "\n  env: mine"));
        assertThat(command.run(new String[]{"configure", PROJECT})).isZero();
        assertThat(home.resolve("config-cvd-2026-0007/afon.properties")).content().contains("ENV_NAME=mine").doesNotContain("reserved");
        assertThat(out.toString()).contains("config-mine");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToConfigureFromAnEnvThatIsNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        Files.writeString(home.resolve("projects/cvd-2026-0007/project.yaml"),
                          ProjectTemplates.example(cloud).replace("cloud: " + c, "cloud: " + c + "\n  env: nowhere"));
        assertThat(command.run(new String[]{"configure", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("config-nowhere", "is not a directory");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToConfigureFromAnEnvGivenButNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"configure", PROJECT, "--env", "missing"})).isEqualTo(1);
        assertThat(err.toString()).contains("config-missing", "is not a directory");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnEnvThatWouldNameAPath(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"configure", PROJECT, "--env", "../x"})).isEqualTo(1);
        assertThat(err.toString()).contains("is not the name of an env");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsOnAMissingFile(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"plan", "no-such-project"})).isEqualTo(1);
        assertThat(err.toString()).contains("No project file", "projects/no-such-project/project.yaml");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAProjectThatIsNotAValidIdAsItWouldNameAPath(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"plan", "../config"})).isEqualTo(2);
        assertThat(err.toString()).contains("is not a lower-case DNS label");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void showsTheUsageForAnUnknownActionOrNoArgs(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"bogus", PROJECT})).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown action 'bogus'", "Usage: manage-project");
        assertThat(command.run(new String[0])).isZero();
        assertThat(out.toString()).contains("Usage: manage-project");
    }

    private String projectHome() {
        return home.resolve("projects/cvd-2026-0007/home").toString();
    }

    private List<String> inProject(String... args) {
        List<String> all = new ArrayList<>(List.of("--home", projectHome(), "--cdktf", home.resolve("cdktf-cvd-2026-0007").toString()));
        all.addAll(List.of(args));
        return all;
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void listsWhatUpWouldRunWithoutRunningIt(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(ran).isEmpty();
        assertThat(out.toString()).contains("[1/4] create: /cli/afon-cli.sh --home " + projectHome(), c + "-create " + c + "-full",
            "[2/4] identity: /cli/afon-cli.sh --home " + projectHome(), "manage-identity create cvd-2026-0007",
            "[3/4] git: skipped, Git is deployed with " + c + "-full, as no stack " + c + "-git is defined in the stacks file",
            "[4/4] tre: /cli/afon-cli.sh --home " + projectHome(), "manage-stack setup " + c + "-full");
        // the identity runs in the project's home too, so that it never uses the config that happens to be active
        assertThat(out.toString().lines().filter(l -> l.contains("manage-identity")).findFirst().orElseThrow()).contains("--home " + projectHome());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rebuildsWhatLivesForADayAndRunsTheCreateScriptOfTheProjectsCloud(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(out.toString()).contains(c + "-create " + c + "-full", "manage-stack setup " + c + "-full");
        out.reset();
        assertThat(command.run(new String[]{"nightly", PROJECT, "--dry-run"})).isZero();
        assertThat(out.toString()).contains("manage-stack delete " + c + "-full", "manage-stack setup " + c + "-full");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void saysNothingOfGitBeingDeployedWithTheTreIfItIsNot(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        treDeploysGit = false;
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(out.toString()).contains("[3/4] git: skipped, no stack " + c + "-git is defined in the stacks file")
                                  .doesNotContain("Git is deployed with");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void hasNoGitStageForAProjectWithNoGitOfItsOwn(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String yaml = ProjectTemplates.example(cloud).replace("    gitBucket: cvd-2026-0007-gitea\n", "")
                           .replace("  git:\n    domain: git.cvd-2026-0007.example.org\n", "");
        Files.writeString(home.resolve("projects/cvd-2026-0007/project.yaml"), yaml);
        assertThat(command.run(new String[]{"plan", PROJECT})).isZero();
        assertThat(out.toString()).doesNotContain("git (");
        out.reset();
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(out.toString()).contains("[3/3] tre:").doesNotContain("git:");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void runsTheStagesInOrderInTheHomeOfTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        assertThat(ran).containsExactly(
            inProject(c + "-create", c + "-full"),
            inProject("manage-identity", "create", PROJECT),
            inProject("manage-stack", "setup", c + "-full"));
        assertThat(home.resolve("projects/cvd-2026-0007/home/config")).isSymbolicLink();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void runsTheGitStageToo_onceItsStackIsDefined(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        gitStackDefined = true;
        configure();
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        assertThat(ran).containsExactly(
            inProject(c + "-create", c + "-full"),
            inProject("manage-identity", "create", PROJECT),
            inProject("manage-stack", "setup", c + "-git"),
            inProject("manage-stack", "setup", c + "-full"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void stopsAtTheFirstFailureAndSaysWhatToResumeFrom(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        status = 3;
        assertThat(command.run(new String[]{"up", PROJECT})).isEqualTo(1);
        assertThat(ran).hasSize(2);
        assertThat(err.toString()).contains("Stage identity failed with exit status 3", "manage-project up cvd-2026-0007 --from identity");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void resumesFromAStage(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT, "--from", "tre"})).isZero();
        assertThat(ran).containsExactly(inProject("manage-stack", "setup", c + "-full"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToRunAProjectThatIsNotConfigured(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"up", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("is not configured", "manage-project configure cvd-2026-0007");
        assertThat(ran).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheStacksDownLastFirstAndLeavesTheRest(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        gitStackDefined = true;
        configure();
        assertThat(command.run(new String[]{"down", PROJECT})).isZero();
        assertThat(ran).containsExactly(inProject("manage-stack", "delete", c + "-full"), inProject("manage-stack", "delete", c + "-git"));
        assertThat(out.toString()).contains("Left as they are", "manage-identity close cvd-2026-0007");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rebuildsWhatLivesForADayAtNight(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        configure();
        assertThat(command.run(new String[]{"nightly", PROJECT})).isZero();
        assertThat(ran).containsExactly(inProject("manage-stack", "delete", c + "-full"), inProject("manage-stack", "setup", c + "-full"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAStageThatIsUnknownOrNotRunByTheAction(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"up", PROJECT, "--from", "bogus"})).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown stage 'bogus'");
        assertThat(command.run(new String[]{"plan", PROJECT, "--from", "tre"})).isEqualTo(2);
        assertThat(command.run(new String[]{"nightly", PROJECT, "--from", "create", "--dry-run"})).isEqualTo(2);
        assertThat(err.toString()).contains("No stage create to resume from");
    }
}
