package health.emrys.afon.ops.project;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.components.GitComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.project.ProjectCommands.Command;
import health.emrys.afon.ops.scripts.ScriptMain;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.FileProjectProvider;
import health.emrys.afon.project.IdentityProject;
import health.emrys.afon.project.ProjectEnv;
import health.emrys.afon.project.ProjectException;
import health.emrys.afon.project.ProjectHome;
import health.emrys.afon.project.ProjectPlan;
import health.emrys.afon.project.ProjectPlan.StageId;
import health.emrys.afon.project.ProjectRole;
import health.emrys.afon.project.ProjectTemplate;
import health.emrys.afon.project.TreProject;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Manages a project, the stacks that Afon deploys together for one research project, as the deployer defines them in
 * {@code project.yaml}, see {@link FileProjectProvider}:
 * <pre>
 * manage-project validate &lt;project&gt;
 * manage-project plan &lt;project&gt;
 * manage-project configure &lt;project&gt; [--made-from &lt;env&gt;]
 * manage-project up &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * manage-project down &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * manage-project nightly &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * manage-project run &lt;project&gt; &lt;command&gt;...
 * </pre>
 * <ul>
 *   <li>{@code validate} checks the file, listing every problem in it;</li>
 *   <li>{@code plan} lists the stages that bring the project up, in order, with the state of each stack, and those that the
 *       nightly flow runs again, see {@link ProjectPlan};</li>
 *   <li>{@code configure} makes the config env of the project, {@code config-<project>} in the Afon home, and its empty cdktf
 *       env {@code cdktf-<project>}, see {@link ProjectEnv}: the config of the env that the project names as its {@code env}, or
 *       {@code --made-from}, else the active config, with the properties derived from the file written over it.  It also makes the home of
 *       the project, see {@link ProjectHome}, so that any command of Afon can run in it before the project is brought up;</li>
 *   <li>{@code up} runs the stages in order, {@code down} deletes the stacks, the last first, and {@code nightly} deletes and sets up again
 *       what lives for a day, see {@link ProjectCommands}.  Each stage runs in the home of the project, see {@link ProjectHome}, which
 *       needs it to be configured, and the first to fail stops the run, with what to resume from: {@code --from <stage>} runs from that
 *       stage, and {@code --dry-run} lists the commands without running them;</li>
 *   <li>{@code run} runs a command of Afon in the home of the project, as {@code afon-cli.sh --home <its home> <command>}, so that it uses
 *       the project's config, address and cluster, whatever env is active; what follows the project is the command, as it is;</li>
 *   <li>{@code <project>} is the id of the project, whose file the deployer writes in its own dir,
 *       {@code <afon-home>/projects/<project>/project.yaml}: it is all that is needed to find the project.</li>
 * </ul>
 * What acts on the project's identity, its clients, group and members, is {@code manage-identity}, not this command.
 */
public class ManageProjectMain {

    /**
     * Not {@code --env}, nor {@code --cloud}: afon-cli.sh takes those, wherever they are, and {@code --env} switches the active env, which
     * running a project must never do.
     */
    private static final String ENV = "--made-from";
    private static final String FROM = "--from";
    private static final String ROLE = "--role";
    private static final String FORCE = "--force";
    private static final String DRY_RUN = "--dry-run";
    private static final List<String> RUNS = List.of("up", "down", "nightly");

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-project <action> <project> [" + ENV + " <env>] [" + FROM + " <stage>] [" + DRY_RUN + "], where:",
        "       manage-project init <project> <cloud> [" + ROLE + " identity|tre] [" + ENV + " <env>] [" + FORCE + "]",
        "  <action> is one of:",
        "    init      writes the project file from the template of its role, for the deployer to complete: what is in <angle brackets>",
        "              is what only the deployer knows, so it is not valid until it is replaced.  Never overwrites a file, unless given",
        "              " + FORCE + ", which keeps the old one beside it as project.yaml.<time>.bak",
        "    validate  checks the project file, listing every problem in it",
        "    plan      lists the stages that bring the project up, in order, with the state of each stack",
        "    configure makes the config env config-<project> in the Afon home, and its empty cdktf env cdktf-<project>: the",
        "              config of the env it is made from with the properties derived from the project file written over it",
        "    up        runs the stages in order, in the home of the project: the first to fail stops the run",
        "    down      deletes the stacks of the project, the last first, and leaves its buckets and its place in the identity",
        "    nightly   deletes and sets up again what lives for a day, the TRE",
        "    run       runs a command of Afon in the home of the project, so that it uses its config, address and cluster: what follows",
        "              the project is the command, eg manage-project run <project> db-whitelist azure --dry-run",
        "  <project> is the id of the project, eg xyz-yyyy-nnnn, whose file the deployer writes in its own dir,",
        "    <afon-home>/" + FileProjectProvider.PROJECTS_DIR + "/<project>/" + FileProjectProvider.FILE,
        "  " + ENV + " is the env whose config configure makes the project from, config-<env>: the env the project names if not given,",
        "    else the active config",
        "  " + FROM + " is the stage that up, down or nightly start from, eg after a failure: create, identity, git or tre",
        "  " + DRY_RUN + " lists the commands that up, down or nightly would run, without running them",
        "Acting on the project's identity, its clients, group and members, is manage-identity.");

    /**
     * Runs an Afon command in a process of its own.
     */
    interface Executor {
        /**
         * @param command the command and its args
         * @return its exit status
         */
        int run(List<String> command) throws IOException;

        /**
         * @param env more env for the process of the command
         * @return its exit status
         */
        default int run(List<String> command, java.util.Map<String, String> env) throws IOException {
            return run(command);
        }
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Path afonHome;
    private final Executor executor;
    private final Supplier<String> cliDir;
    private final ProjectCommands.Stacks stacks;

    ManageProjectMain(PrintStream out, PrintStream err, Path afonHome, Executor executor, Supplier<String> cliDir,
                      ProjectCommands.Stacks stacks) {
        this.out = out;
        this.err = err;
        this.afonHome = afonHome;
        this.executor = executor;
        this.cliDir = cliDir;
        this.stacks = stacks;
    }

    /**
     * Runs the command.
     *
     * @return the exit status: 0 if it succeeded, 1 if the file is not valid or cannot be read, or a stage failed, 2 if the usage is wrong
     */
    int run(String[] args) {
        if (args.length == 0 || List.of(args).contains("-h") || List.of(args).contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        if (args[0].equals("init")) {
            return init(args);
        }
        if (args[0].equals("run")) {
            // what follows the project is the command to run, whatever it has: its options are not this one's
            return runInProject(args);
        }
        List<String> positional = new ArrayList<>();
        String env = null;
        String from = null;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(ENV) && i + 1 < args.length) {
                env = args[++i];
            } else if (args[i].equals(FROM) && i + 1 < args.length) {
                from = args[++i];
            } else if (args[i].equals(DRY_RUN)) {
                dryRun = true;
            } else if (args[i].startsWith("--")) {
                return usage("Unknown option, or no value for " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        String action = args[0];
        if (!List.of("validate", "plan", "configure", "up", "down", "nightly").contains(action)) {
            return usage("Unknown action '" + action + "'");
        }
        if (positional.size() != 1) {
            return usage(action + " takes one <project>");
        }
        StageId fromStage = null;
        if (from != null) {
            if (!RUNS.contains(action)) {
                return usage(FROM + " is for up, down and nightly");
            }
            try {
                fromStage = StageId.valueOf(from.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return usage("Unknown stage '" + from + "': one of " + java.util.Arrays.stream(StageId.values()).map(id -> id.name().toLowerCase(Locale.ROOT)).toList());
            }
        }
        FileProjectProvider provider;
        try {
            provider = FileProjectProvider.forProject(afonHome, positional.get(0));
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project file %s: write the project's %s in its dir%n", provider.file(), FileProjectProvider.FILE);
            return 1;
        }
        AfonProject project;
        try {
            project = provider.getProject();
        } catch (ProjectException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
        return switch (action) {
            case "validate" -> {
                out.printf("%s is a valid %s project: %s on %s, made from %s%s%n", provider.file(), project.role(), project.id(), project.cloud(),
                           project.env() == null ? "the active config" : "config-" + project.env(),
                           project instanceof TreProject tre ? ", with " + tre.providers().size() + " provider(s)" : "");
                yield 0;
            }
            case "plan" -> {
                print(ProjectPlan.of(project));
                yield 0;
            }
            case "configure" -> configure(project, provider.file(), env != null ? env : project.env());
            default -> runStages(action, project, fromStage, dryRun);
        };
    }

    /**
     * Writes the project file from the template of the role, in the project's own dir, which it makes.
     */
    private int init(String[] args) {
        String role = ProjectRole.TRE.value();
        String env = null;
        boolean force = false;
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(FORCE)) {
                force = true;
            } else if (args[i].equals(ROLE) && i + 1 < args.length) {
                role = args[++i];
            } else if (args[i].equals(ENV) && i + 1 < args.length) {
                env = args[++i];
            } else if (args[i].startsWith("--")) {
                return usage("Unknown option, or no value for " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() != 2) {
            return usage("init takes a <project> and a <cloud>, one of " + java.util.Arrays.stream(AfonCloud.values())
                                                                                        .map(c -> c.toString().toLowerCase(Locale.ROOT)).toList());
        }
        String cloud = positional.get(1);
        Optional<ProjectRole> projectRole = ProjectRole.of(role);
        if (projectRole.isEmpty()) {
            return usage(ROLE + " should be identity or tre, not '" + role + "'");
        }
        AfonCloud projectCloud;
        try {
            projectCloud = AfonCloud.valueOf(cloud.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return usage("<cloud> should be one of " + java.util.Arrays.stream(AfonCloud.values())
                                                              .map(c -> c.toString().toLowerCase(Locale.ROOT)).toList() + ", not '" + cloud + "'");
        }
        FileProjectProvider provider;
        try {
            provider = FileProjectProvider.forProject(afonHome, positional.get(0));
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (provider.exists() && !force) {
            err.printf("%s exists already: not overwriting.  " + FORCE + " replaces it, keeping the old one beside it%n", provider.file());
            return 1;
        }
        try {
            Path backup = provider.exists() ? backUp(provider.file()) : null;
            if (backup != null) {
                out.printf("Kept the old file as %s%n", backup);
            }
            String content = ProjectTemplate.render(Path.of(cliDir.get(), ProjectTemplate.DIR), projectRole.get(), projectCloud,
                                                    positional.get(0), env);
            Files.createDirectories(provider.file().getParent());
            Files.writeString(provider.file(), content, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | UncheckedIOException | IOException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.printf("Wrote %s from the template of the %s role: complete what is in <angle brackets>, then%n", provider.file(), projectRole.get().value());
        out.printf("  afon-cli.sh manage-project validate %s%n  afon-cli.sh manage-project configure %s%n", positional.get(0), positional.get(0));
        return 0;
    }

    /**
     * Moves the file beside itself, as {@code <file>.<time>.bak}, or with a number after the time if that is taken, so that a backup is never
     * replaced.
     *
     * @return the backup
     */
    private static Path backUp(Path file) throws IOException {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path backup = file.resolveSibling(file.getFileName() + "." + stamp + ".bak");
        for (int n = 1; Files.exists(backup); n++) {
            backup = file.resolveSibling(file.getFileName() + "." + stamp + "-" + n + ".bak");
        }
        return Files.move(file, backup);
    }

    private int usage(String problem) {
        err.println(problem);
        err.println(USAGE);
        return 2;
    }

    /**
     * @param env the env whose config the project is made from, or {@code null} for the active config
     */
    private int configure(AfonProject project, Path file, String env) {
        try {
            Path envConfig = env != null ? ProjectEnv.envConfigDir(afonHome, env) : Path.of(ConfigUtils.configDir());
            Path config = ProjectEnv.configure(project, envConfig, afonHome, file);
            Path projectHome = ProjectHome.prepare(afonHome, project);
            out.printf("Configured project %s: its config env %s was made from %s, with its cdktf dir %s%n", project.id(), config, envConfig,
                       ProjectEnv.cdktfDir(afonHome, project));
            out.printf("Its commands run in its home %s, where any command of Afon can too: afon-cli.sh manage-project run %s <command>%n",
                       projectHome, project.id());
            out.println("Not configured yet: the Providers' project catalogs, which the stacks do not read from the project file.");
            return 0;
        } catch (ProjectException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private int runStages(String action, AfonProject project, StageId from, boolean dryRun) {
        ProjectPlan plan = ProjectPlan.of(project);
        IdentityProject identityProject;
        try {
            identityProject = action.equals("up") ? identityProjectOf(project) : null;
        } catch (ProjectException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
        List<Command> commands;
        try {
            commands = ProjectCommands.from(switch (action) {
                case "up" -> ProjectCommands.up(plan, stacks, identityProject, identityProject == null ? null
                    : ProjectEnv.cdktfDir(afonHome, identityProject).resolve("kube").resolve(identityProject.stack() + ".config"));
                case "down" -> ProjectCommands.down(plan, stacks);
                default -> ProjectCommands.nightly(plan, stacks);
            }, from);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (commands.isEmpty()) {
            out.printf("Nothing to run: no stage of project %s %s%n", project.id(),
                       action.equals("nightly") ? "lives for a day, so none is rebuilt at night" : "is left for " + action);
            return 0;
        }
        if (!dryRun) {
            try {
                ProjectHome.prepare(afonHome, project);
                if (identityProject != null) {
                    ProjectHome.prepare(afonHome, identityProject);
                }
            } catch (IllegalStateException | UncheckedIOException e) {
                err.println(e.getMessage());
                return 1;
            }
        }
        String cli = Path.of(cliDir.get(), "afon-cli.sh").toString();
        int number = 0;
        for (Command command : commands) {
            number++;
            String stage = command.stage().name().toLowerCase(Locale.ROOT);
            String label = String.format("[%d/%d] %s", number, commands.size(), stage);
            if (command.skipped()) {
                out.printf("%s: skipped, %s%n", label, command.note());
                continue;
            }
            int status = runCommand(cli, command, label, dryRun);
            if (status == FAILED_TO_RUN) {
                return 1;
            }
            if (status == FAILED_BEFORE) {
                err.printf("Stage %s could not be prepared.  Resume with: manage-project %s %s --from %s%n", stage, action, project.id(), stage);
                return 1;
            }
            if (status != 0) {
                err.printf("Stage %s failed with exit status %d.  Resume with: manage-project %s %s --from %s%n", stage, status, action,
                           project.id(), stage);
                return 1;
            }
        }
        if (action.equals("down")) {
            if (project instanceof IdentityProject) {
                out.println("Left as they are: the reserved egress IP, if the project names one, and the project's config and cdktf dirs.  The projects that"
                            + " used this identity are registered in it no more: bring it up again, then run their register stage");
            } else {
                out.println("Left as they are: the project's buckets, and its clients and group in the central identity, until it is closed:"
                            + " manage-project run " + project.id() + " manage-identity close");
            }
        }
        return 0;
    }

    /** What {@link #runCommand} says instead of an exit status of a command of the stage: the commands before it did not all succeed. */
    private static final int FAILED_BEFORE = -1;
    /** What it says if a command could not be started. */
    private static final int FAILED_TO_RUN = -3;

    /**
     * Runs the commands of a stage: those before it, which stop it at the first to fail, then its own, which stop at the first to fail.
     *
     * @return 0 if all succeeded, the exit status of the first of its own commands to fail, or {@link #FAILED_BEFORE} or {@link #FAILED_TO_RUN}
     */
    private int runCommand(String cli, Command command, String label, boolean dryRun) {
        for (ProjectCommands.Invocation before : command.before()) {
            int status = runInvocation(cli, before, label + " (before)", dryRun);
            if (status == FAILED_TO_RUN) {
                return status;
            }
            if (status != 0) {
                err.printf("%s failed with exit status %d%n", String.join(" ", before.args()), status);
                return FAILED_BEFORE;
            }
        }
        for (ProjectCommands.Invocation run : command.run()) {
            int status = runInvocation(cli, run, label, dryRun);
            if (status != 0) {
                return status;
            }
        }
        return 0;
    }

    /**
     * Prints the command, and runs it unless it is a dry run.
     *
     * @return its exit status, or {@link #FAILED_TO_RUN} if it could not be started
     */
    private int runInvocation(String cli, ProjectCommands.Invocation invocation, String label, boolean dryRun) {
        List<String> argv = argv(cli, invocation.project(), invocation.args());
        out.printf("%s: %s%n", label, String.join(" ", argv));
        if (dryRun) {
            return 0;
        }
        try {
            return executor.run(argv);
        } catch (IOException e) {
            err.printf("Cannot run %s: %s%n", cli, e.getMessage());
            return FAILED_TO_RUN;
        }
    }

    /**
     * @return the command line that runs a command of Afon in the home of a project: its home and its cdktf, which are those of the project
     */
    private List<String> argv(String cli, String project, List<String> args) {
        List<String> argv = new ArrayList<>(List.of(cli, "--home", FileProjectProvider.projectDir(afonHome, project).resolve(ProjectHome.HOME_DIR).toString(),
                                                    "--cdktf", afonHome.resolve(ProjectEnv.CDKTF_PREFIX + project).toString()));
        argv.addAll(args);
        return argv;
    }

    /**
     * @return the identity project that the TRE project names, or {@code null} if it is not a TRE project, or names none
     * @throws ProjectException      if the identity project's file is not valid
     * @throws IllegalStateException if the project it names is not an identity project of the cloud of this one, or has no file
     */
    private IdentityProject identityProjectOf(AfonProject project) {
        if (!(project instanceof TreProject tre) || tre.identity().project() == null) {
            return null;
        }
        String id = tre.identity().project();
        FileProjectProvider provider = FileProjectProvider.forProject(afonHome, id);
        if (!provider.exists()) {
            throw new IllegalStateException(String.format("Project %s names the identity project %s, which has no file %s", project.id(), id,
                                                          provider.file()));
        }
        if (!(provider.getProject() instanceof IdentityProject identity)) {
            throw new IllegalStateException(String.format("Project %s names %s as its identity project, but %s has the role %s", project.id(), id,
                                                          id, provider.getProject().role()));
        }
        if (identity.cloud() != project.cloud()) {
            throw new IllegalStateException(String.format("Project %s is on %s, but the identity project %s it names is on %s", project.id(),
                                                          project.cloud(), id, identity.cloud()));
        }
        return identity;
    }

    /**
     * Runs a command of Afon in the home of the project, as {@code afon-cli.sh --home <its home> <command>} does, so that it uses its config,
     * its address and its cluster, whatever env is active.  The args are the command's as they are, none is for this one.
     *
     * @return the exit status of the command
     */
    private int runInProject(String[] args) {
        if (args.length < 3) {
            return usage("run takes a <project> and a command of Afon to run in its home");
        }
        FileProjectProvider provider;
        try {
            provider = FileProjectProvider.forProject(afonHome, args[1]);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project file %s: write the project's %s in its dir%n", provider.file(), FileProjectProvider.FILE);
            return 1;
        }
        AfonProject project;
        try {
            project = provider.getProject();
            ProjectHome.prepare(afonHome, project);
        } catch (ProjectException | IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
        List<String> command = new ArrayList<>(List.of(args).subList(2, args.length));
        List<String> argv = argv(Path.of(cliDir.get(), "afon-cli.sh").toString(), args[1], command);
        out.println(String.join(" ", argv));
        try {
            // the cloud of the project, which the launcher takes from AFON_CLOUD, as it takes --cloud, which the launcher of this command has taken
            // from the command's args, so that the commands that need a cloud, eg cdktf-store, need not be given it
            int status = executor.run(argv, java.util.Map.of("AFON_CLOUD", project.cloud().toString().toLowerCase(Locale.ROOT)));
            if (status != 0) {
                err.printf("%s failed with exit status %d%n", command.get(0), status);
            }
            return status;
        } catch (IOException e) {
            err.printf("Cannot run %s: %s%n", argv.get(0), e.getMessage());
            return 1;
        }
    }

    private void print(ProjectPlan plan) {
        out.printf("Project %s on %s, with the role %s%n", plan.project().id(), plan.project().cloud(), plan.project().role());
        out.println("Up, in order (down is the reverse):");
        int number = 1;
        for (ProjectPlan.Stage stage : plan.stages()) {
            out.printf("  %d. %s%s%s%n", number++, stage,
                       stage.stateKey() == null ? "" : ", state " + stage.stateKey(),
                       stage.nightly() ? ", deleted and set up again nightly" : "");
        }
    }

    public static void main(String[] args) {
        int status = new ManageProjectMain(System.out, System.err, ProjectEnv.defaultAfonHome(), new ProcessExecutor(), ScriptMain::afonCliDir,
                                           new ProjectCommands.Stacks(
                                               stack -> StackDefinitions.find(stack).isPresent(),
                                               stack -> StackDefinitions.find(stack).map(d -> d.declares(GitComponent.class)).orElse(false))).run(args);
        if (status != 0) {
            System.exit(status);
        }
    }
}
