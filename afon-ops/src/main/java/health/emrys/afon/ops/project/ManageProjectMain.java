package health.emrys.afon.ops.project;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.components.GitComponent;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.project.ProjectCommands.Command;
import health.emrys.afon.ops.scripts.ScriptMain;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.FileProjectProvider;
import health.emrys.afon.project.ProjectEnv;
import health.emrys.afon.project.ProjectException;
import health.emrys.afon.project.ProjectHome;
import health.emrys.afon.project.ProjectPlan;
import health.emrys.afon.project.ProjectPlan.StageId;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Manages a project, the stacks that Afon deploys together for one research project, as the deployer defines them in
 * {@code project.yaml}, see {@link FileProjectProvider}:
 * <pre>
 * manage-project validate &lt;project&gt;
 * manage-project plan &lt;project&gt;
 * manage-project configure &lt;project&gt; [--env &lt;env&gt;]
 * manage-project up &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * manage-project down &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * manage-project nightly &lt;project&gt; [--from &lt;stage&gt;] [--dry-run]
 * </pre>
 * <ul>
 *   <li>{@code validate} checks the file, listing every problem in it;</li>
 *   <li>{@code plan} lists the stages that bring the project up, in order, with the state of each stack, and those that the
 *       nightly flow runs again, see {@link ProjectPlan};</li>
 *   <li>{@code configure} makes the config env of the project, {@code config-<project>} in the Afon home, and its empty cdktf
 *       env {@code cdktf-<project>}, see {@link ProjectEnv}: the config of the env that the project names as its {@code env}, or
 *       {@code --env}, else the active config, with the properties derived from the file written over it;</li>
 *   <li>{@code up} runs the stages in order, {@code down} deletes the stacks, the last first, and {@code nightly} deletes and sets up again
 *       what lives for a day, see {@link ProjectCommands}.  Each stage runs in the home of the project, see {@link ProjectHome}, which
 *       needs it to be configured, and the first to fail stops the run, with what to resume from: {@code --from <stage>} runs from that
 *       stage, and {@code --dry-run} lists the commands without running them;</li>
 *   <li>{@code <project>} is the id of the project, whose file the deployer writes in its own dir,
 *       {@code <afon-home>/projects/<project>/project.yaml}: it is all that is needed to find the project.</li>
 * </ul>
 * What acts on the project's identity, its clients, group and members, is {@code manage-identity}, not this command.
 */
public class ManageProjectMain {

    private static final String ENV = "--env";
    private static final String FROM = "--from";
    private static final String DRY_RUN = "--dry-run";
    private static final List<String> RUNS = List.of("up", "down", "nightly");

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-project <action> <project> [" + ENV + " <env>] [" + FROM + " <stage>] [" + DRY_RUN + "], where:",
        "  <action> is one of:",
        "    validate  checks the project file, listing every problem in it",
        "    plan      lists the stages that bring the project up, in order, with the state of each stack",
        "    configure makes the config env config-<project> in the Afon home, and its empty cdktf env cdktf-<project>: the",
        "              config of the env it is made from with the properties derived from the project file written over it",
        "    up        runs the stages in order, in the home of the project: the first to fail stops the run",
        "    down      deletes the stacks of the project, the last first, and leaves its buckets and its place in the identity",
        "    nightly   deletes and sets up again what lives for a day, the TRE",
        "  <project> is the id of the project, eg cvd-2026-0007, whose file the deployer writes in its own dir,",
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
                return usage("Unknown stage '" + from + "': one of create, identity, git or tre");
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
                out.printf("%s is a valid project: %s on %s, made from %s, with %d provider(s)%n", provider.file(), project.id(), project.cloud(),
                           project.env() == null ? "the active config" : "config-" + project.env(), project.providers().size());
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
            out.printf("Configured project %s: its config env %s was made from %s, with its cdktf dir %s%n", project.id(), config, envConfig,
                       ProjectEnv.cdktfDir(afonHome, project));
            out.println("Not configured yet: the Providers' project catalogs, which the stacks do not read from the project file.");
            return 0;
        } catch (ProjectException | IllegalArgumentException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private int runStages(String action, AfonProject project, StageId from, boolean dryRun) {
        ProjectPlan plan = ProjectPlan.of(project);
        List<Command> commands;
        try {
            commands = ProjectCommands.from(switch (action) {
                case "up" -> ProjectCommands.up(plan, stacks);
                case "down" -> ProjectCommands.down(plan, stacks);
                default -> ProjectCommands.nightly(plan, stacks);
            }, from);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        Path home;
        try {
            home = dryRun ? ProjectHome.dir(afonHome, project) : ProjectHome.prepare(afonHome, project);
        } catch (IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
        Path cdktf = ProjectEnv.cdktfDir(afonHome, project);
        String cli = Path.of(cliDir.get(), "afon-cli.sh").toString();
        int number = 0;
        for (Command command : commands) {
            number++;
            String stage = command.stage().name().toLowerCase(Locale.ROOT);
            if (command.skipped()) {
                out.printf("[%d/%d] %s: skipped, %s%n", number, commands.size(), stage, command.note());
                continue;
            }
            List<String> argv = new ArrayList<>();
            argv.add(cli);
            if (command.inProject()) {
                argv.addAll(List.of("--home", home.toString(), "--cdktf", cdktf.toString()));
            }
            argv.addAll(command.args());
            out.printf("[%d/%d] %s: %s%n", number, commands.size(), stage, String.join(" ", argv));
            if (dryRun) {
                continue;
            }
            int status;
            try {
                status = executor.run(argv);
            } catch (IOException e) {
                err.printf("Cannot run %s: %s%n", cli, e.getMessage());
                return 1;
            }
            if (status != 0) {
                err.printf("Stage %s failed with exit status %d.  Resume with: manage-project %s %s --from %s%n", stage, status, action,
                           project.id(), stage);
                return 1;
            }
        }
        if (action.equals("down")) {
            out.println("Left as they are: the project's buckets, and its clients and group in the central identity, until it is closed:"
                        + " manage-identity close " + project.id());
        }
        return 0;
    }

    private void print(ProjectPlan plan) {
        out.printf("Project %s on %s%n", plan.project().id(), plan.project().cloud());
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
