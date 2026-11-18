package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.ops.project.ProjectTemplates.lower;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test is run on every cloud, see {@link ProjectTemplates}.
 */
class ManageProjectMainTest {

    private static final String PROJECT = "xyz-yyyy-nnnn";

    @TempDir
    Path home;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private ManageProjectMain command;
    /** The commands that were run, each as its args after the launcher. */
    private final List<List<String>> ran = new ArrayList<>();
    private int status;
    /** The env that the commands were given, when they were given some. */
    private final List<Map<String, String>> envs = new ArrayList<>();
    private java.util.function.Predicate<List<String>> failing = argv -> false;
    private boolean gitStackDefined;
    private boolean treDeploysGit = true;

    /** Writes the project of the example on the cloud, and makes the command that acts on it. */
    private void setUp(AfonCloud cloud) throws Exception {
        ProjectTemplates.write(home, PROJECT, cloud);
        ManageProjectMain.Executor executor = new ManageProjectMain.Executor() {
            @Override
            public int run(List<String> argv) {
                ran.add(argv.subList(1, argv.size()));
                // the registration can be made to fail with status, and any command with failing
                return failing.test(argv.subList(1, argv.size())) ? 4 : argv.contains("manage-identity") ? status : 0;
            }

            @Override
            public int run(List<String> argv, Map<String, String> env) {
                envs.add(env);
                return run(argv);
            }
        };
        command = new ManageProjectMain(new PrintStream(out), new PrintStream(err), home, executor, () -> "/cli",
                                        new ProjectCommands.Stacks(stack -> gitStackDefined || !stack.type().equals("git"), stack -> treDeploysGit));
    }

    private void configure() throws Exception {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        assertThat(command.run(new String[]{"configure", PROJECT, "--made-from", "dev"})).isZero();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void validatesAProjectFile(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"validate", PROJECT})).isZero();
        assertThat(out.toString()).contains("is a valid tre project: xyz-yyyy-nnnn on " + lower(cloud) + ", made from the active config, with 2 Data Provider catalog(s)");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheStagesInOrderWithTheirState(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"plan", PROJECT})).isZero();
        assertThat(out.toString()).contains(
            "1. create (" + c + "-create), state xyz-yyyy-nnnn/" + c + "-create",
            "2. register",
            "3. git (" + c + "-git), state xyz-yyyy-nnnn/" + c + "-git",
            "4. tre (" + c + "-full), state xyz-yyyy-nnnn/" + c + "-full, deleted and set up again nightly");
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
        assertThat(err.toString()).contains("project.id 'xyz-yyyy-nnnn' is not 'other-project', the name of the dir that holds it");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheConfigAndCdktfEnvsOfTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        assertThat(command.run(new String[]{"configure", PROJECT, "--made-from", "dev"})).isZero();
        assertThat(home.resolve("config-xyz-yyyy-nnnn/afon.properties")).content().contains("ENV_NAME=dev", "PROJECT_ID=xyz-yyyy-nnnn");
        assertThat(home.resolve("cdktf-xyz-yyyy-nnnn")).isDirectory();
        assertThat(out.toString()).contains("Configured project xyz-yyyy-nnnn", "Not configured yet",
                                            "Its commands run in its home " + home.resolve("projects/xyz-yyyy-nnnn/home"),
                                            "afon-cli.sh manage-project run xyz-yyyy-nnnn <command>");
        // so that a command of Afon can run in the project before it is brought up, eg to whitelist its egress IP
        assertThat(home.resolve("projects/xyz-yyyy-nnnn/home/config")).isSymbolicLink();
        assertThat(home.resolve("projects/xyz-yyyy-nnnn/home/cdktf")).isSymbolicLink();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresFromTheEnvTheProjectNamesNotTheActiveOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        Files.createDirectories(home.resolve("config-reserved"));
        Files.writeString(home.resolve("config-reserved/afon.properties"), "ENV_NAME=reserved\n" + ProjectEnvAccounts.ACCOUNTS);
        Files.createDirectories(home.resolve("config-mine"));
        Files.writeString(home.resolve("config-mine/afon.properties"), "ENV_NAME=mine\n" + ProjectEnvAccounts.ACCOUNTS);
        // the active config is another env's, which the project must not be made from
        Files.createSymbolicLink(home.resolve("config"), home.resolve("config-reserved"));
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("cloud: " + c, "cloud: " + c + "\n  env: mine"));
        assertThat(command.run(new String[]{"configure", PROJECT})).isZero();
        assertThat(home.resolve("config-xyz-yyyy-nnnn/afon.properties")).content().contains("ENV_NAME=mine").doesNotContain("reserved");
        assertThat(out.toString()).contains("config-mine");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToConfigureFromAnEnvThatIsNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("cloud: " + c, "cloud: " + c + "\n  env: nowhere"));
        assertThat(command.run(new String[]{"configure", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("config-nowhere", "is not a directory");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToConfigureFromAnEnvGivenButNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"configure", PROJECT, "--made-from", "missing"})).isEqualTo(1);
        assertThat(err.toString()).contains("config-missing", "is not a directory");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnEnvThatWouldNameAPath(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"configure", PROJECT, "--made-from", "../x"})).isEqualTo(1);
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
        return home.resolve("projects/xyz-yyyy-nnnn/home").toString();
    }

    private List<String> inProject(String... args) {
        List<String> all = new ArrayList<>(List.of("--home", projectHome(), "--cdktf", home.resolve("cdktf-xyz-yyyy-nnnn").toString()));
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
            "[2/4] register: /cli/afon-cli.sh --home " + projectHome(), "manage-identity create xyz-yyyy-nnnn",
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
        String yaml = ProjectTemplates.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", "")
                           .replace("  git:\n    domain: git.xyz-yyyy-nnnn.example.org\n", "");
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"), yaml);
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
            inProject("db-whitelist", c),
            inProject("dns-create-zones", c, c + "-full"),
            inProject("manage-identity", "create", PROJECT),
            inProject("manage-identity", "sync-members", PROJECT),
            inProject("manage-stack", "setup", c + "-full"));
        assertThat(home.resolve("projects/xyz-yyyy-nnnn/home/config")).isSymbolicLink();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void syncsTheMembersAfterTheRegistration(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        assertThat(ran).containsSubsequence(inProject("manage-identity", "create", PROJECT), inProject("manage-identity", "sync-members", PROJECT),
                                           inProject("manage-stack", "setup", c + "-full"));
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
            inProject("db-whitelist", c),
            // the zones of the domains of each stack that serves one
            inProject("dns-create-zones", c, c + "-git"),
            inProject("dns-create-zones", c, c + "-full"),
            inProject("manage-identity", "create", PROJECT),
            inProject("manage-identity", "sync-members", PROJECT),
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
        assertThat(ran).hasSize(4);
        assertThat(err.toString()).contains("Stage register failed with exit status 3", "manage-project up xyz-yyyy-nnnn --from register");
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
        assertThat(err.toString()).contains("is not configured", "manage-project configure xyz-yyyy-nnnn");
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
        assertThat(out.toString()).contains("Left as they are", "manage-project run xyz-yyyy-nnnn manage-identity close");
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

    // the identity project

    private void setUpIdentity(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectTemplates.writeIdentity(home, "idp", cloud);
    }

    private void configureIdentity() throws Exception {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        assertThat(command.run(new String[]{"configure", "idp", "--made-from", "dev"})).isZero();
    }

    private List<String> inIdentity(String... args) {
        List<String> all = new ArrayList<>(List.of("--home", home.resolve("projects/idp/home").toString(), "--cdktf",
                                                   home.resolve("cdktf-idp").toString()));
        all.addAll(List.of(args));
        return all;
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void validatesAndPlansTheIdentityProject(AfonCloud cloud) throws Exception {
        setUpIdentity(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"validate", "idp"})).isZero();
        assertThat(out.toString()).contains("is a valid identity project: idp on " + c + ", made from the active config")
                                  .doesNotContain("provider");
        out.reset();
        assertThat(command.run(new String[]{"plan", "idp"})).isZero();
        assertThat(out.toString()).contains("Project idp on " + c + ", with the role identity",
            "1. create (" + c + "-create), state idp/" + c + "-create",
            "2. identity (" + c + "-keycloak), state idp/" + c + "-keycloak");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void bringsUpTheIdentityProjectAsItsCreateScriptAndItsStack(AfonCloud cloud) throws Exception {
        setUpIdentity(cloud);
        configureIdentity();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", "idp"})).isZero();
        assertThat(ran).containsExactly(inIdentity(c + "-create", c + "-keycloak"), inIdentity("db-whitelist", c),
            inIdentity("dns-create-zones", c, c + "-keycloak"),
            inIdentity("manage-stack", "setup", c + "-keycloak"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesDownTheIdentityProjectByItsStackAndRebuildsNothingAtNight(AfonCloud cloud) throws Exception {
        setUpIdentity(cloud);
        configureIdentity();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"down", "idp"})).isZero();
        assertThat(ran).containsExactly(inIdentity("manage-stack", "delete", c + "-keycloak"));
        // the identity has no clients or group to close: what is left is the projects registered in it, which must register again
        assertThat(out.toString()).contains("Left as they are", "then run their register stage").doesNotContain("manage-identity close");
        ran.clear();
        assertThat(command.run(new String[]{"nightly", "idp"})).isZero();
        assertThat(ran).isEmpty();
        assertThat(out.toString()).contains("Nothing to run: no stage of project idp lives for a day");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void resumesTheIdentityProjectFromItsStack(AfonCloud cloud) throws Exception {
        setUpIdentity(cloud);
        configureIdentity();
        assertThat(command.run(new String[]{"up", "idp", "--from", "identity"})).isZero();
        assertThat(ran).containsExactly(inIdentity("manage-stack", "setup", lower(cloud) + "-keycloak"));
        // it has no tre stage to resume from
        assertThat(command.run(new String[]{"up", "idp", "--from", "tre"})).isEqualTo(2);
        assertThat(err.toString()).contains("No stage tre to resume from");
    }

    // the commands of Afon run in the home of a project

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void runsACommandOfAfonInTheHomeOfTheProjectWithItsOptionsAsTheyAre(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"run", PROJECT, "db-whitelist", lower(cloud), "--dry-run"})).isZero();
        assertThat(ran).containsExactly(inProject("db-whitelist", lower(cloud), "--dry-run"));
        assertThat(out.toString()).contains("/cli/afon-cli.sh --home " + projectHome(), "db-whitelist " + lower(cloud) + " --dry-run");
        // the project has a cloud, so the commands that need one are not given it
        assertThat(envs).containsExactly(Map.of("AFON_CLOUD", lower(cloud)));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void returnsTheExitStatusOfACommandItRuns(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        status = 3;
        assertThat(command.run(new String[]{"run", PROJECT, "manage-identity", "close", PROJECT})).isEqualTo(3);
        assertThat(err.toString()).contains("manage-identity failed with exit status 3");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsACommandAndAConfiguredProjectToRunOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"run", PROJECT})).isEqualTo(2);
        assertThat(err.toString()).contains("run takes a <project> and a command");
        assertThat(command.run(new String[]{"run", PROJECT, "db-whitelist"})).isEqualTo(1);
        assertThat(err.toString()).contains("is not configured", "manage-project configure " + PROJECT);
        assertThat(ran).isEmpty();
    }

    // the steps that only exist because of the project

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void whitelistsTheEgressIpOnlyIfTheProjectNamesOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("  egressIp: 203.0.113.10\n", ""));
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(out.toString()).doesNotContain("db-whitelist");
    }

    private static final String NAMES_THE_IDENTITY = "identity:\n  project: idp\n";

    /** The project, which names the identity project, and the identity project, both configured. */
    private void setUpWithIdentity(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectTemplates.writeIdentity(home, "idp", cloud);
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("identity:\n", NAMES_THE_IDENTITY));
        configure();
        assertThat(command.run(new String[]{"configure", "idp", "--made-from", "dev"})).isZero();
    }

    private List<String> inCentralIdentity(String... args) {
        return inIdentity(args);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void registersThroughAPortForwardInTheClusterOfTheIdentityProject(AfonCloud cloud) throws Exception {
        setUpWithIdentity(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        // the kubeconfig that the identity project's home writes is the one that the registration reaches Keycloak through
        String kubeconfig = home.resolve("cdktf-idp/kube/" + c + "-keycloak.config").toString();
        assertThat(ran).containsExactly(
            inProject(c + "-create", c + "-full"), inProject("db-whitelist", c), inProject("dns-create-zones", c, c + "-full"),
            inCentralIdentity(c + "-update-kube-config", c + "-keycloak"),
            inProject("manage-identity", "create", PROJECT, "--kubeconfig", kubeconfig),
            inProject("manage-identity", "sync-members", PROJECT, "--kubeconfig", kubeconfig),
            // the TRE's stacks configure that Keycloak too, through the kubeconfig that the config names
            inCentralIdentity(c + "-update-kube-config", c + "-keycloak"),
            inProject("manage-stack", "setup", c + "-full"));
        // no address is admitted to Keycloak, so there is nothing to take off
        assertThat(ran).noneMatch(c2 -> c2.contains("kube-keycloak-whitelist"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void doesNotRegisterIfTheKubeconfigOfTheIdentityCannotBeWritten(AfonCloud cloud) throws Exception {
        setUpWithIdentity(cloud);
        failing = argv -> argv.contains(lower(cloud) + "-update-kube-config");
        assertThat(command.run(new String[]{"up", PROJECT})).isEqualTo(1);
        assertThat(ran).noneMatch(c -> c.contains("manage-identity"));
        assertThat(err.toString()).contains("could not be prepared", "--from register");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void stopsAtTheRegistrationIfItFails(AfonCloud cloud) throws Exception {
        setUpWithIdentity(cloud);
        status = 3;
        assertThat(command.run(new String[]{"up", PROJECT})).isEqualTo(1);
        assertThat(ran).last().satisfies(c -> assertThat(c).contains("manage-identity"));
        assertThat(err.toString()).contains("Stage register failed with exit status 3", "--from register");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void listsTheStepBeforeTheRegistrationInADryRun(AfonCloud cloud) throws Exception {
        setUpWithIdentity(cloud);
        ran.clear();
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        assertThat(ran).isEmpty();
        assertThat(out.toString()).contains("register (before): /cli/afon-cli.sh --home " + home.resolve("projects/idp/home"),
                                            "register: /cli/afon-cli.sh --home " + projectHome(), "manage-identity create " + PROJECT + " --kubeconfig ");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdentityProjectThatIsNotThereOrIsNotOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("identity:\n", NAMES_THE_IDENTITY));
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isEqualTo(1);
        assertThat(err.toString()).contains("names the identity project idp, which has no file");

        // a project that is not an identity project
        Files.createDirectories(home.resolve("projects/idp"));
        Files.writeString(home.resolve("projects/idp/project.yaml"),
                          ProjectTemplates.example(cloud).replace("xyz-yyyy-nnnn", "idp"));
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isEqualTo(1);
        assertThat(err.toString()).contains("but idp has the role tre");

        assertThat(ran).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void usesAnIdentityProjectOfAnotherCloudByItsOwnKubeConfig(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("identity:\n", NAMES_THE_IDENTITY));
        AfonCloud other = AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
        ProjectTemplates.writeIdentity(home, "idp", other);
        assertThat(command.run(new String[]{"up", PROJECT, "--dry-run"})).isZero();
        // the kubeconfig of the identity's cluster is written by the script of the identity's cloud, for its stack, in its own home
        assertThat(out.toString()).contains(lower(other) + "-update-kube-config " + lower(other) + "-keycloak")
                                  .doesNotContain(lower(cloud) + "-update-kube-config");
        assertThat(ran).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void doesNotAdmitThisMachineIfTheProjectNamesNoIdentityProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        assertThat(ran).noneMatch(c -> c.contains("--kubeconfig"));
    }

    // init writes the project file from the template of the role

    /** A command whose CLI dir has the templates of the repository, as the distribution has them. */
    private ManageProjectMain commandWithTemplates() throws Exception {
        Path cli = Files.createDirectories(home.resolve("cli"));
        Path templates = Files.createDirectories(cli.resolve("templates/project"));
        try (var files = Files.list(Path.of("..", "templates", "project"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, templates.resolve(file.getFileName()));
            }
        }
        return new ManageProjectMain(new PrintStream(out), new PrintStream(err), home, argv -> 0, () -> cli.toString(),
                                     new ProjectCommands.Stacks(stack -> true, stack -> false));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void initWritesTheProjectFileFromTheTemplateOfTheRole(AfonCloud cloud) throws Exception {
        ManageProjectMain init = commandWithTemplates();
        assertThat(init.run(new String[]{"init", "abc-def", lower(cloud), "--made-from", "dev"})).isZero();
        Path file = home.resolve("projects/abc-def/project.yaml");
        assertThat(file).content().contains("id: abc-def", "cloud: " + lower(cloud), "env: dev", "<identity-project>");
        assertThat(init.run(new String[]{"init", "idp", lower(cloud), "--role", "identity"})).isZero();
        assertThat(home.resolve("projects/idp/project.yaml")).content().contains("role: identity", "env: <env>", "<egress-ip>");
        assertThat(init.run(new String[]{"init", "lon", lower(cloud), "--role", "data", "--made-from", "dev"})).isZero();
        assertThat(home.resolve("projects/lon/project.yaml")).content().contains("role: data", "env: dev", "<egress-ip>", "catalogs: []");
        assertThat(out.toString()).contains("complete what is in <angle brackets>", "manage-project validate abc-def");
        // what is left to complete is what validate says is wrong
        assertThat(init.run(new String[]{"validate", "abc-def"})).isEqualTo(1);
        assertThat(err.toString()).contains("not a valid project");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void initNeedsACloudAndKnowsTheRoles(AfonCloud cloud) throws Exception {
        ManageProjectMain init = commandWithTemplates();
        assertThat(init.run(new String[]{"init", "abc-def"})).isEqualTo(2);
        assertThat(err.toString()).contains("init takes a <project> and a <cloud>");
        assertThat(init.run(new String[]{"init", "abc-def", "mars"})).isEqualTo(2);
        assertThat(err.toString()).contains("<cloud> should be one of");
        assertThat(init.run(new String[]{"init", "abc-def", lower(cloud), "--role", "boss"})).isEqualTo(2);
        assertThat(err.toString()).contains("--role should be identity, tre or data");
        assertThat(init.run(new String[]{"init", "Bad_Id", lower(cloud)})).isEqualTo(2);
        assertThat(home.resolve("projects")).doesNotExist();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void initNeverOverwritesAProjectFile(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path file = home.resolve("projects/" + PROJECT + "/project.yaml");
        String before = Files.readString(file);
        assertThat(commandWithTemplates().run(new String[]{"init", PROJECT, lower(cloud)})).isEqualTo(1);
        assertThat(err.toString()).contains("exists already: not overwriting", "--force");
        assertThat(file).hasContent(before);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void initForceReplacesTheFileKeepingTheOldOneBesideIt(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path file = home.resolve("projects/" + PROJECT + "/project.yaml");
        String before = Files.readString(file);
        ManageProjectMain init = commandWithTemplates();
        assertThat(init.run(new String[]{"init", PROJECT, lower(cloud), "--force"})).isZero();
        assertThat(file).content().contains("<identity-project>").isNotEqualTo(before);
        // twice in the same second: the first backup is not replaced
        assertThat(init.run(new String[]{"init", PROJECT, lower(cloud), "--force"})).isZero();
        try (var files = Files.list(file.getParent())) {
            var backups = files.filter(f -> f.getFileName().toString().matches("project\\.yaml\\.\\d{14}(-\\d+)?\\.bak")).toList();
            assertThat(backups).hasSize(2);
            assertThat(backups).anySatisfy(b -> assertThat(b).hasContent(before));
        }
        assertThat(out.toString()).contains("Kept the old file as");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void initForceWritesTheFileOfAProjectThatHasNone(AfonCloud cloud) throws Exception {
        assertThat(commandWithTemplates().run(new String[]{"init", "abc-def", lower(cloud), "--force"})).isZero();
        assertThat(home.resolve("projects/abc-def")).isDirectoryContaining("glob:**/project.yaml").isDirectoryNotContaining("glob:**/*.bak");
    }

    // the names of a project's resources

    private static String accountOf(AfonCloud cloud) {
        return switch (cloud) {
            case AZURE -> "sub-1";
            case AWS -> "111111111111";
            case GCP -> "gcp-proj";
        };
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void listsTheNamesOfTheResourcesOfTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"names", PROJECT, "--made-from", "dev"})).isZero();
        String c = lower(cloud);
        assertThat(out.toString()).contains("Names of the resources of project xyz-yyyy-nnnn on " + c + ", made from config-dev",
                                            "cluster of " + c + "-full", "full-xyz-yyyy-nnnn-cluster", "CLUSTER_NAME");
        if (cloud == AfonCloud.AZURE) {
            assertThat(out.toString()).contains("AZURE_STORAGE_ACCOUNT", "afonstorage" + health.emrys.afon.project.ProjectNames.token(cloud, accountOf(cloud), PROJECT));
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void findsTheProjectThatOwnsAGlobalName(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Files.writeString(home.resolve("projects/" + PROJECT + "/project.yaml"),
                          ProjectTemplates.example(cloud).replaceFirst("(cloud: [a-z]+\n)", "$1  env: dev\n"));
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        String name = cloud == AfonCloud.AZURE ? "afonstorage" + health.emrys.afon.project.ProjectNames.token(cloud, accountOf(cloud), PROJECT) : null;
        if (name == null) {
            // no catalog bucket of the cloud in this env: nothing is named but the cluster, which is not unique in the whole cloud
            assertThat(command.run(new String[]{"find", "afon-0123456789-aws"})).isEqualTo(1);
            assertThat(err.toString()).contains("No project in");
            return;
        }
        assertThat(command.run(new String[]{"find", name.toUpperCase()})).isZero();
        assertThat(out.toString()).contains(name.toUpperCase() + " is the AZURE_STORAGE_ACCOUNT of project xyz-yyyy-nnnn on azure");
        assertThat(command.run(new String[]{"find", "another-name"})).isEqualTo(1);
        assertThat(command.run(new String[]{"find"})).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToValidateOrConfigureAProjectWhoseDomainAnotherProjectHas(AfonCloud cloud) throws Exception {
        setUp(cloud);
        // the same file under another id: the same domains
        Files.createDirectories(home.resolve("projects/other-project"));
        Files.writeString(home.resolve("projects/other-project/project.yaml"), ProjectTemplates.example(cloud).replaceFirst("id: xyz-yyyy-nnnn", "id: other-project"));
        assertThat(command.run(new String[]{"validate", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("is also that of other-project", "one domain serves one project");
        Files.createDirectories(home.resolve("config-dev"));
        assertThat(command.run(new String[]{"configure", PROJECT, "--made-from", "dev"})).isEqualTo(1);
        assertThat(home.resolve("config-xyz-yyyy-nnnn")).doesNotExist();
    }

    // an address serves one cluster

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void warnsOfProjectsThatWouldLeaveFromTheSameAddress(AfonCloud cloud) throws Exception {
        setUp(cloud);
        // two projects of the cloud, neither naming an address, made from an env that has one
        Files.createDirectories(home.resolve("projects/other-project"));
        Files.writeString(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"),
                          ProjectTemplates.example(cloud).replace("  egressIp: 203.0.113.10\n", "").replaceFirst("(cloud: [a-z]+\n)", "$1  env: dev\n"));
        Files.writeString(home.resolve("projects/other-project/project.yaml"),
                          ProjectTemplates.example(cloud).replace("  egressIp: 203.0.113.10\n", "").replaceFirst("(cloud: [a-z]+\n)", "$1  env: dev\n")
                              .replace("xyz-yyyy-nnnn", "other-project"));
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS + cloud.name() + "_EGRESS_IP=203.0.113.5\n");
        // a warning, not an error: they need not be up together
        assertThat(command.run(new String[]{"validate", PROJECT})).isZero();
        assertThat(err.toString()).contains("WARNING: project xyz-yyyy-nnnn and project other-project both leave from 203.0.113.5",
                                            "cannot be up at the same time", lower(cloud) + "-egress-ip create");
        // one that names its own leaves from it, so there is no warning
        err.reset();
        Files.writeString(home.resolve("projects/other-project/project.yaml"),
                          Files.readString(home.resolve("projects/other-project/project.yaml")).replaceFirst("(cloud: [a-z]+\n)", "$1  egressIp: 203.0.113.99\n"));
        assertThat(command.run(new String[]{"validate", PROJECT})).isZero();
        assertThat(err.toString()).doesNotContain("WARNING");
    }

    // the delegation of the zones of the project's domains

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void delegatesTheZonesFromTheParentDomainWhenTheProjectAsksForRoute53(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path file = home.resolve("projects/xyz-yyyy-nnnn/project.yaml");
        Files.writeString(file, Files.readString(file) + "dns:\n  delegation: route53\n");
        gitStackDefined = true;
        configure();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "create"})).isZero();
        List<List<String>> expected = new ArrayList<>(List.of(inProject(c + "-create", c + "-full"), inProject("db-whitelist", c)));
        // each stack that serves a domain: its zones, then their delegation, so that the stages after can get their certificates, which on AWS, whose
        // stacks look up the issued ACM certificate of each of their domains, are issued once the zone is delegated
        for (String stack : List.of(c + "-git", c + "-full")) {
            expected.add(inProject("dns-create-zones", c, stack));
            expected.add(inProject("dns-delegate", c, stack, "--route53"));
            if (cloud == AfonCloud.AWS) {
                expected.add(inProject("aws-issue-cert", "--stack", stack));
            }
        }
        assertThat(ran).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void issuesNoCertificatesWhereTheDelegationIsLeftToTheDeployer(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "create"})).isZero();
        assertThat(ran).noneMatch(c -> c.contains("aws-issue-cert"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheDelegationToTheDeployerByDefault(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT})).isZero();
        assertThat(ran).noneMatch(c -> c.contains("dns-delegate"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void delegatesTheZoneOfTheIdentityProjectToo(AfonCloud cloud) throws Exception {
        setUpIdentity(cloud);
        Path file = home.resolve("projects/idp/project.yaml");
        Files.writeString(file, Files.readString(file) + "dns:\n  delegation: route53\n");
        configureIdentity();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", "idp", "--until", "create"})).isZero();
        assertThat(ran).contains(inIdentity("dns-create-zones", c, c + "-keycloak"), inIdentity("dns-delegate", c, c + "-keycloak", "--route53"));
        if (cloud == AfonCloud.AWS) {
            assertThat(ran).contains(inIdentity("aws-issue-cert", "--stack", "aws-keycloak"));
        } else {
            assertThat(ran).noneMatch(r -> r.contains("aws-issue-cert"));
        }
    }

    // --until stops after a stage

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void stopsAfterTheStageGivenAndSaysHowToContinue(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "create"})).isZero();
        assertThat(ran).containsExactly(inProject(c + "-create", c + "-full"), inProject("db-whitelist", c), inProject("dns-create-zones", c, c + "-full"));
        assertThat(out.toString()).contains("Stopped after stage create, as asked.  Continue with: manage-project up xyz-yyyy-nnnn --from register");
        // and the rest is run from there
        ran.clear();
        assertThat(command.run(new String[]{"up", PROJECT, "--from", "register"})).isZero();
        assertThat(ran).containsExactly(inProject("manage-identity", "create", PROJECT), inProject("manage-identity", "sync-members", PROJECT),
                                          inProject("manage-stack", "setup", c + "-full"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void runsOnlyTheStagesBetweenFromAndUntil(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT, "--from", "register", "--until", "register"})).isZero();
        assertThat(ran).containsExactly(inProject("manage-identity", "create", PROJECT), inProject("manage-identity", "sync-members", PROJECT));
        assertThat(out.toString()).contains("[1/1] register:", "Continue with: manage-project up xyz-yyyy-nnnn --from tre");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void saysNothingOfContinuingWhenTheLastStageIsTheOneGiven(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "tre"})).isZero();
        assertThat(out.toString()).doesNotContain("Stopped after");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesDownUpToAStageInTheReverseOrder(AfonCloud cloud) throws Exception {
        setUp(cloud);
        gitStackDefined = true;
        configure();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"down", PROJECT, "--until", "tre"})).isZero();
        assertThat(ran).containsExactly(inProject("manage-stack", "delete", c + "-full"));
        assertThat(out.toString()).contains("Stopped after stage tre, as asked.  Continue with: manage-project down xyz-yyyy-nnnn --from git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnUntilThatIsNotAStageOfWhatIsRunOrNotForTheAction(AfonCloud cloud) throws Exception {
        setUp(cloud);
        configure();
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "nowhere"})).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown stage 'nowhere'");
        assertThat(command.run(new String[]{"up", PROJECT, "--until", "identity"})).isEqualTo(2);
        assertThat(err.toString()).contains("No stage identity to stop after");
        assertThat(command.run(new String[]{"validate", PROJECT, "--until", "tre"})).isEqualTo(2);
        assertThat(err.toString()).contains("--until is for up, down and nightly");
        assertThat(ran).isEmpty();
    }

    // the data project

    private void setUpData(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectTemplates.writeData(home, "lon", cloud);
    }

    private void configureData() throws Exception {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        assertThat(command.run(new String[]{"configure", "lon", "--made-from", "dev"})).isZero();
    }

    private List<String> inData(String... args) {
        List<String> all = new ArrayList<>(List.of("--home", home.resolve("projects/lon/home").toString(), "--cdktf",
                                                   home.resolve("cdktf-lon").toString()));
        all.addAll(List.of(args));
        return all;
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void validatesAndPlansTheDataProject(AfonCloud cloud) throws Exception {
        setUpData(cloud);
        String c = lower(cloud);
        assertThat(command.run(new String[]{"validate", "lon"})).isZero();
        assertThat(out.toString()).contains("is a valid data project: lon on " + c + ", made from the active config").doesNotContain("provider");
        out.reset();
        assertThat(command.run(new String[]{"plan", "lon"})).isZero();
        assertThat(out.toString()).contains("Project lon on " + c + ", with the role data", "1. create (" + c + "-create), state lon/" + c + "-create",
                                            "2. data (" + c + "-polaris), state lon/" + c + "-polaris");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void bringsUpTheDataProjectAsItsCreateScriptAndItsPolarisStack(AfonCloud cloud) throws Exception {
        setUpData(cloud);
        configureData();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"up", "lon"})).isZero();
        assertThat(ran).containsExactly(inData(c + "-create", c + "-polaris"), inData("db-whitelist", c),
            inData("dns-create-zones", c, c + "-polaris"), inData("manage-stack", "setup", c + "-polaris"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesDownTheDataProjectLeavingItsStorageAndRebuildsNothingAtNight(AfonCloud cloud) throws Exception {
        setUpData(cloud);
        configureData();
        String c = lower(cloud);
        assertThat(command.run(new String[]{"down", "lon"})).isZero();
        assertThat(ran).containsExactly(inData("manage-stack", "delete", c + "-polaris"));
        assertThat(out.toString()).contains("Left as they are: the Data Provider's storage").doesNotContain("manage-identity close");
        ran.clear();
        assertThat(command.run(new String[]{"nightly", "lon"})).isZero();
        assertThat(ran).isEmpty();
        assertThat(out.toString()).contains("Nothing to run: no stage of project lon lives for a day");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheDataProjectWithItsCatalogsWithoutTheMessageOfAnUnreadTreProvider(AfonCloud cloud) throws Exception {
        setUpData(cloud);
        configureData();
        assertThat(out.toString()).contains("Configured project lon").doesNotContain("Not configured yet");
        assertThat(Files.readString(home.resolve("config-lon").resolve("catalogs-" + lower(cloud) + ".properties")))
            .contains(cloud.name() + "_CATALOG_IDS=cvd,dbt");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void bringsUpADataProjectThatNamesTheIdentityProjectWritingItsKubeConfigFirst(AfonCloud cloud) throws Exception {
        setUp(cloud);
        AfonCloud other = AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
        ProjectTemplates.writeIdentity(home, "idp", other);
        Path file = home.resolve("projects/lon/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ProjectTemplates.dataExample(cloud).replace("identity: {", "identity: {project: idp, "));
        assertThat(command.run(new String[]{"up", "lon", "--dry-run"})).isZero();
        String c = lower(cloud);
        String o = lower(other);
        assertThat(out.toString()).contains("[2/2] data (before): ", o + "-update-kube-config " + o + "-keycloak", "manage-stack setup " + c + "-polaris");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void validatesATreProjectThatNamesTheCatalogOfADataProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String c = lower(cloud);
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvAccounts.ACCOUNTS);
        Files.writeString(env.resolve("afon-" + c + ".properties"), cloud.name() + "_POLARIS_DOMAIN_NAME=" + c + "-polaris.dev.click\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        Path data = home.resolve("projects/lon/project.yaml");
        Files.createDirectories(data.getParent());
        Files.writeString(data, ProjectTemplates.dataExample(cloud).replace("role: data\n", "role: data\n  env: dev\n"));
        Path file = home.resolve("projects/" + PROJECT + "/project.yaml");
        Files.writeString(file, Files.readString(file).replaceAll("(?s)dataProviders:.*?\nmembers:", "dataProviders:\n  named:\n    - {project: lon, catalogs: [cvd]}\nmembers:"));
        assertThat(command.run(new String[]{"validate", PROJECT})).isZero();
        assertThat(out.toString()).contains("is a valid tre project", "with 1 Data Provider catalog(s)");

        // a Data Provider's project that is not there is a problem of the file
        Files.delete(data);
        out.reset();
        assertThat(command.run(new String[]{"validate", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("dataProviders.named[0].project names lon, which has no file");
    }
}
