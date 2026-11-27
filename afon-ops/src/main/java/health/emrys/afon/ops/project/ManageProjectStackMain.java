package health.emrys.afon.ops.project;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.components.GitComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.project.ProjectStackCommands.Command;
import health.emrys.afon.ops.scripts.ScriptMain;
import health.emrys.afon.project.ProjectStack;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.DataProjectStack;
import health.emrys.afon.project.IdentityProjectStack;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.project.ProjectStackException;
import health.emrys.afon.project.ProjectStackHome;
import health.emrys.afon.project.ProjectStackNames;
import health.emrys.afon.project.ProjectStackPlan;
import health.emrys.afon.project.ProjectStackPlan.StageId;
import health.emrys.afon.project.ProjectStackRole;
import health.emrys.afon.project.ProjectStackTemplate;
import health.emrys.afon.project.TreProjectStack;

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
 * Manages a project stack, a stack that Afon deploys for one role of a project (its identity, a Data Provider or its TRE), as the deployer
 * defines it in {@code project.yaml}, see {@link FileProjectStackProvider}:
 * <pre>
 * manage-project-stack validate &lt;project-stack&gt;
 * manage-project-stack plan &lt;project-stack&gt;
 * manage-project-stack configure &lt;project-stack&gt; [--made-from &lt;env&gt;]
 * manage-project-stack up &lt;project-stack&gt; [--from &lt;stage&gt;[:&lt;step&gt;]] [--until &lt;stage&gt;] [--dry-run]
 * manage-project-stack down &lt;project-stack&gt; [--from &lt;stage&gt;[:&lt;step&gt;]] [--until &lt;stage&gt;] [--dry-run]
 * manage-project-stack nightly &lt;project-stack&gt; [--from &lt;stage&gt;] [--until &lt;stage&gt;] [--dry-run]
 * manage-project-stack run &lt;project-stack&gt; &lt;command&gt;...
 * </pre>
 * <ul>
 *   <li>{@code validate} checks the file, listing every problem in it;</li>
 *   <li>{@code plan} lists the stages that bring the project stack up, in order, with the state of each stack, and those that the
 *       nightly flow runs again, see {@link ProjectStackPlan};</li>
 *   <li>{@code configure} makes the config env of the project stack, {@code config-<project-stack>} in the Afon home, and its empty cdktf
 *       env {@code cdktf-<project-stack>}, see {@link ProjectStackEnv}: the config of the env that the project stack names as its {@code env}, or
 *       {@code --made-from}, else the active config, with the properties derived from the file written over it.  It also makes the home of
 *       the project stack, see {@link ProjectStackHome}, so that any command of Afon can run in it before the project stack is brought up;</li>
 *   <li>{@code up} runs the stages in order, {@code down} deletes the stacks, the last first, and {@code nightly} deletes and sets up again
 *       what lives for a day, see {@link ProjectStackCommands}.  Each stage runs in the home of the project stack, see {@link ProjectStackHome}, which
 *       needs it to be configured, and the first to fail stops the run, with what to resume from: {@code --from <stage>} runs from that
 *       stage, {@code --from <stage>:<step>} (for {@code up} and {@code down}, and a stage of a stack) from that step of the stack's own, as
 *       {@code manage-stack setup <stack> <step>} does, so that a deploy that succeeded is not done again, and {@code --dry-run} lists the commands
 *       without running them;</li>
 *   <li>{@code egress-ip create|show|delete} reserves, shows, or releases, the egress IP of the project stack, in the cloud of its file, named after it, which its file asks
 *       for before it can be configured: the next step after {@code init}; it releases none that a stack uses;</li>
 *   <li>{@code run} runs a command of Afon in the home of the project stack, as {@code afon-cli.sh --home <its home> <command>}, so that it uses
 *       the project stack's config, address and cluster, whatever env is active; what follows the project stack is the command, as it is;</li>
 *   <li>{@code <project-stack>} is the id of the project stack, whose file the deployer writes in its own dir,
 *       {@code <afon-home>/projects/<project-stack>/project.yaml}: it is all that is needed to find the project stack.</li>
 * </ul>
 * What acts on the project stack's identity, its clients, group and members, is {@code manage-identity}, not this command.
 */
public class ManageProjectStackMain {

    /**
     * Not {@code --env}, nor {@code --cloud}: afon-cli.sh takes those, wherever they are, and {@code --env} switches the active env, which
     * running a project stack must never do.
     */
    private static final String ENV = "--made-from";
    private static final String FROM = "--from";
    private static final String UNTIL = "--until";
    private static final String ROLE = "--role";
    private static final String FORCE = "--force";
    private static final String DRY_RUN = "--dry-run";
    private static final List<String> RUNS = List.of("up", "down", "nightly");

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-project-stack <action> <project-stack> [" + ENV + " <env>] [" + FROM + " <stage>[:<step>]] [" + UNTIL + " <stage>] [" + DRY_RUN + "], where:",
        "       manage-project-stack find <name>",
        "       manage-project-stack egress-ip <create|show|delete> <project-stack> [" + ENV + " <env>]",
        "       manage-project-stack init <project-stack> <cloud> [" + ROLE + " identity|tre|data] [" + ENV + " <env>] [" + FORCE + "]",
        "  <action> is one of:",
        "    init      writes the project stack file from the template of its role, for the deployer to complete: what is in <angle brackets>",
        "              is what only the deployer knows, so it is not valid until it is replaced.  Never overwrites a file, unless given",
        "              " + FORCE + ", which keeps the old one beside it as project.yaml.<time>.bak",
        "    egress-ip create reserves the egress IP of the project stack in its cloud, named afon-egress-ip-<project-stack>, and show prints its address, to be put in the",
        "              file as egressIp: it is what the file asks for before it is valid and can be configured, so it is the step after init.  delete releases it,",
        "              unless a stack uses it, which it says and leaves it: delete that stack first",
        "    validate  checks the project stack file, listing every problem in it",
        "    names     lists the names of the project stack's resources: its clusters, and what is unique in the whole cloud, which are made of a",
        "              token of the cloud, account and project stack, eg afonstorage<token>.  manage-project-stack find <name> says which project stack owns a name",
        "    plan      lists the stages that bring the project stack up, in order, with the state of each stack",
        "    configure makes the config env config-<project-stack> in the Afon home, and its empty cdktf env cdktf-<project-stack>: the",
        "              config of the env it is made from with the properties derived from the project stack file written over it",
        "    up        runs the stages in order, in the home of the project stack: the first to fail stops the run",
        "    down      deletes the stacks of the project stack, the last first, and leaves its buckets and its place in the identity",
        "    nightly   deletes and sets up again what lives for a day, the TRE",
        "    run       runs a command of Afon in the home of the project stack, so that it uses its config, address and cluster: what follows",
        "              the project stack is the command, eg manage-project-stack run <project-stack> db-whitelist azure --dry-run",
        "  <project-stack> is the id of the project stack, eg xyz-yyyy-nnnn, whose file the deployer writes in its own dir,",
        "    <afon-home>/" + FileProjectStackProvider.PROJECTS_DIR + "/<project-stack>/" + FileProjectStackProvider.FILE,
        "  " + ENV + " is the env whose config configure makes the project stack from, config-<env>: the env the project stack names if not given,",
        "    else the active config",
        "  " + FROM + " is the stage that up, down or nightly start from, eg after a failure: create, identity, git or tre.  For up and down, a stage that",
        "    sets up or deletes a stack, identity, git, tre or data, can be given the step of the stack to resume from, as <stage>:<step>, eg",
        "    tre:configure_oidc_users_for_cloud_aws, so that a deploy that succeeded is not done again: the steps are those that manage-stack shows",
        "  " + UNTIL + " is the last stage that up, down or nightly run, eg to stop after create and delegate the DNS zones before the rest:",
        "    the stages after it are left for " + FROM + ", which the message at the end of the run gives",
        "  " + DRY_RUN + " lists the commands that up, down or nightly would run, without running them",
        "Acting on the project stack's identity, its clients, group and members, is manage-identity.");

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
    private final ProjectStackCommands.Stacks stacks;

    ManageProjectStackMain(PrintStream out, PrintStream err, Path afonHome, Executor executor, Supplier<String> cliDir,
                      ProjectStackCommands.Stacks stacks) {
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
        if (args[0].equals("find")) {
            return find(args);
        }
        if (args[0].equals("run")) {
            // what follows the project stack is the command to run, whatever it has: its options are not this one's
            return runInProject(args);
        }
        if (args[0].equals("egress-ip")) {
            return egressIp(args);
        }
        List<String> positional = new ArrayList<>();
        String env = null;
        String from = null;
        String fromStep = null;
        String until = null;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(ENV) && i + 1 < args.length) {
                env = args[++i];
            } else if (args[i].equals(FROM) && i + 1 < args.length) {
                from = args[++i];
            } else if (args[i].equals(UNTIL) && i + 1 < args.length) {
                until = args[++i];
            } else if (args[i].equals(DRY_RUN)) {
                dryRun = true;
            } else if (args[i].startsWith("--")) {
                return usage("Unknown option, or no value for " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        String action = args[0];
        if (!List.of("validate", "plan", "names", "configure", "up", "down", "nightly").contains(action)) {
            return usage("Unknown action '" + action + "'");
        }
        if (positional.size() != 1) {
            return usage(action + " takes one <project-stack>");
        }
        StageId fromStage = null;
        StageId untilStage = null;
        for (String[] option : new String[][]{{FROM, from}, {UNTIL, until}}) {
            if (option[1] == null) {
                continue;
            }
            if (!RUNS.contains(action)) {
                return usage(option[0] + " is for up, down and nightly");
            }
            String stageName = option[1];
            if (option[0].equals(FROM) && stageName.contains(":")) {
                if (!List.of("up", "down").contains(action)) {
                    return usage("A step to resume from, " + FROM + " <stage>:<step>, is for up and down");
                }
                fromStep = stageName.substring(stageName.indexOf(':') + 1);
                stageName = stageName.substring(0, stageName.indexOf(':'));
            }
            try {
                StageId stage = StageId.valueOf(stageName.toUpperCase(Locale.ROOT));
                if (option[0].equals(FROM)) {
                    fromStage = stage;
                } else {
                    untilStage = stage;
                }
            } catch (IllegalArgumentException e) {
                return usage("Unknown stage '" + stageName + "': one of " + java.util.Arrays.stream(StageId.values()).map(id -> id.name().toLowerCase(Locale.ROOT)).toList());
            }
        }
        FileProjectStackProvider provider;
        try {
            provider = FileProjectStackProvider.forProjectStack(afonHome, positional.get(0));
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project stack file %s: write the project stack's %s in its dir%n", provider.file(), FileProjectStackProvider.FILE);
            return 1;
        }
        ProjectStack projectStack;
        try {
            projectStack = provider.getProjectStack();
        } catch (ProjectStackException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
        return switch (action) {
            case "names" -> names(projectStack, env != null ? env : projectStack.env());
            case "validate" -> {
                List<String> collisions = ProjectStackNames.domainCollisions(projectStack, otherProjectStacks());
                if (!collisions.isEmpty()) {
                    collisions.forEach(err::println);
                    yield 1;
                }
                warnOfSharedEgressIp(projectStack);
                out.printf("%s is a valid %s project stack: %s on %s, made from %s%s%n", provider.file(), projectStack.role(), projectStack.id(), projectStack.cloud(),
                           projectStack.env() == null ? "the active config" : "config-" + projectStack.env(),
                           projectStack instanceof TreProjectStack tre ? ", with " + tre.dataProviders().size() + " Data Provider catalog(s)" : "");
                yield 0;
            }
            case "plan" -> {
                print(ProjectStackPlan.of(projectStack));
                yield 0;
            }
            case "configure" -> configure(projectStack, provider.file(), env != null ? env : projectStack.env());
            default -> runStages(action, projectStack, provider.file(), fromStage, fromStep, untilStage, dryRun);
        };
    }

    /**
     * @return the other project stacks in the Afon home, ie those of its project stacks dir that have a valid file
     */
    private List<ProjectStack> otherProjectStacks() {
        List<ProjectStack> projectStacks = new ArrayList<>();
        Path dir = afonHome.resolve(FileProjectStackProvider.PROJECTS_DIR);
        if (!Files.isDirectory(dir)) {
            return projectStacks;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                Path file = child.resolve(FileProjectStackProvider.FILE);
                if (Files.isRegularFile(file)) {
                    try {
                        projectStacks.add(new FileProjectStackProvider(file).getProjectStack());
                    } catch (ProjectStackException | IllegalStateException | IllegalArgumentException e) {
                        // a project stack whose file is not valid has no domains to meet
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return projectStacks;
    }

    /**
     * @return the address the project stack's cluster leaves from: the one it names, else the {@code <CLOUD>_EGRESS_IP} of the config it is made from,
     * which every project stack made from that env inherits, or {@code null} if there is none
     */
    private String effectiveEgressIp(ProjectStack projectStack) {
        if (projectStack.egressIp() != null) {
            return projectStack.egressIp();
        }
        try {
            String inherited = envPropertiesOf(projectStack, projectStack.env()).getProperty(projectStack.cloud().name() + "_EGRESS_IP", "").trim();
            return inherited.isEmpty() ? null : inherited;
        } catch (ProjectStackException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            return null;
        }
    }

    /**
     * A warning for each other project stack of the cloud that leaves from the same address, as one reserved address serves one cluster: they cannot be
     * up at the same time.  Not an error, as project stacks need not be.
     */
    private void warnOfSharedEgressIp(ProjectStack projectStack) {
        String ip = effectiveEgressIp(projectStack);
        if (ip == null) {
            return;
        }
        for (ProjectStack other : otherProjectStacks()) {
            if (!other.id().equals(projectStack.id()) && other.cloud() == projectStack.cloud() && ip.equals(effectiveEgressIp(other))) {
                err.printf("WARNING: project stack %s and project stack %s both leave from %s, %s, and one address serves one cluster, so they cannot be up at the same "
                           + "time: reserve another with %s-egress-ip create <name>, and name it in the egressIp of one of them%n",
                           projectStack.id(), other.id(), ip, projectStack.egressIp() == null ? "that of the config they are made from"
                                                                                   : "which " + projectStack.id() + " names",
                           projectStack.cloud().toString().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The config the project stack is made from, with its references resolved, or what it is where there is none. */
    private java.util.Properties envPropertiesOf(ProjectStack projectStack, String env) {
        return ProjectStackEnv.envProperties(env != null ? ProjectStackEnv.envConfigDir(afonHome, env) : Path.of(ConfigUtils.configDir()));
    }

    private int names(ProjectStack projectStack, String env) {
        try {
            java.util.Map<String, String> names = ProjectStackNames.names(projectStack, envPropertiesOf(projectStack, env));
            out.printf("Names of the resources of project stack %s on %s, made from %s:%n", projectStack.id(), projectStack.cloud(),
                       env == null ? "the active config" : "config-" + env);
            names.forEach((name, value) -> out.printf("  %-34s %s%n", name, value));
            return 0;
        } catch (ProjectStackException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    /**
     * Says which project stack a name that is unique in the whole cloud, eg a storage account, belongs to.
     */
    private int find(String[] args) {
        if (args.length != 2 || args[1].startsWith("--")) {
            return usage("find takes one <name>, eg a storage account or a bucket");
        }
        int found = 0;
        for (ProjectStack projectStack : otherProjectStacks()) {
            try {
                java.util.Optional<String> property = ProjectStackNames.propertyOf(projectStack, envPropertiesOf(projectStack, projectStack.env()), args[1]);
                if (property.isPresent()) {
                    out.printf("%s is the %s of project stack %s on %s%n", args[1], property.get(), projectStack.id(), projectStack.cloud());
                    found++;
                }
            } catch (ProjectStackException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
                err.printf("Cannot name the resources of project stack %s: %s%n", projectStack.id(), e.getMessage());
            }
        }
        if (found == 0) {
            err.printf("No project stack in %s has a resource named %s%n", afonHome.resolve(FileProjectStackProvider.PROJECTS_DIR), args[1]);
            return 1;
        }
        return 0;
    }

    /**
     * Writes the project stack file from the template of the role, in the project stack's own dir, which it makes.
     */
    private int init(String[] args) {
        String role = ProjectStackRole.TRE.value();
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
            return usage("init takes a <project-stack> and a <cloud>, one of " + java.util.Arrays.stream(AfonCloud.values())
                                                                                        .map(c -> c.toString().toLowerCase(Locale.ROOT)).toList());
        }
        String cloud = positional.get(1);
        Optional<ProjectStackRole> projectRole = ProjectStackRole.of(role);
        if (projectRole.isEmpty()) {
            return usage(ROLE + " should be identity, tre or data, not '" + role + "'");
        }
        AfonCloud projectCloud;
        try {
            projectCloud = AfonCloud.valueOf(cloud.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return usage("<cloud> should be one of " + java.util.Arrays.stream(AfonCloud.values())
                                                              .map(c -> c.toString().toLowerCase(Locale.ROOT)).toList() + ", not '" + cloud + "'");
        }
        FileProjectStackProvider provider;
        try {
            provider = FileProjectStackProvider.forProjectStack(afonHome, positional.get(0));
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
            String content = ProjectStackTemplate.render(Path.of(cliDir.get(), ProjectStackTemplate.DIR), projectRole.get(), projectCloud,
                                                    positional.get(0), env);
            Files.createDirectories(provider.file().getParent());
            Files.writeString(provider.file(), content, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | UncheckedIOException | IOException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.printf("Wrote %s from the template of the %s role: complete what is in <angle brackets>, starting with the egress IP, which%n", provider.file(), projectRole.get().value());
        out.printf("  afon-cli.sh manage-project-stack egress-ip create %s%n  afon-cli.sh manage-project-stack egress-ip show %s%n", positional.get(0), positional.get(0));
        out.printf("reserves and shows, to put in the file as egressIp, then%n  afon-cli.sh manage-project-stack validate %s%n  afon-cli.sh manage-project-stack configure %s%n", positional.get(0), positional.get(0));
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
     * @param env the env whose config the project stack is made from, or {@code null} for the active config
     */
    private int configure(ProjectStack projectStack, Path file, String env) {
        try {
            List<String> collisions = ProjectStackNames.domainCollisions(projectStack, otherProjectStacks());
            if (!collisions.isEmpty()) {
                collisions.forEach(err::println);
                return 1;
            }
            Path envConfig = env != null ? ProjectStackEnv.envConfigDir(afonHome, env) : Path.of(ConfigUtils.configDir());
            Path config = ProjectStackEnv.configure(projectStack, envConfig, afonHome, file);
            warnOfSharedEgressIp(projectStack);
            Path projectHome = ProjectStackHome.prepare(afonHome, projectStack);
            out.printf("Configured project stack %s: its config env %s was made from %s, with its cdktf dir %s%n", projectStack.id(), config, envConfig,
                       ProjectStackEnv.cdktfDir(afonHome, projectStack));
            out.printf("Its commands run in its home %s, where any command of Afon can too: afon-cli.sh manage-project-stack run %s <command>%n",
                       projectHome, projectStack.id());
            if (projectStack instanceof TreProjectStack) {
                out.println("Not configured yet: the Data Providers' project stack catalogs, which the stacks do not read from the project stack file.");
            }
            return 0;
        } catch (ProjectStackException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private int runStages(String action, ProjectStack projectStack, Path projectFile, StageId from, String step, StageId until, boolean dryRun) {
        ProjectStackPlan plan = ProjectStackPlan.of(projectStack);
        IdentityProjectStack identityProjectStack;
        try {
            identityProjectStack = action.equals("up") ? identityProjectStackOf(projectStack) : null;
        } catch (ProjectStackException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
        List<Command> commands;
        List<Command> left;
        try {
            List<Command> all = ProjectStackCommands.from(switch (action) {
                case "up" -> ProjectStackCommands.up(plan, stacks, identityProjectStack, identityProjectStack == null ? null
                    : ProjectStackEnv.cdktfDir(afonHome, identityProjectStack).resolve("kube").resolve(identityProjectStack.stack() + ".config"));
                case "down" -> ProjectStackCommands.down(plan, stacks);
                default -> ProjectStackCommands.nightly(plan, stacks);
            }, from);
            if (step != null) {
                all = ProjectStackCommands.resumeAt(all, step);
            }
            commands = ProjectStackCommands.until(all, until);
            left = all.subList(commands.size(), all.size());
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (commands.isEmpty()) {
            out.printf("Nothing to run: no stage of project stack %s %s%n", projectStack.id(),
                       action.equals("nightly") ? "lives for a day, so none is rebuilt at night" : "is left for " + action);
            return 0;
        }
        // the stacks read the copy of the project stack's file in its config, so a file changed since `configure` is not what they would deploy.  The registration reads the
        // file itself, so it alone is made
        if (action.equals("up") && ProjectStackEnv.fileIsNewerThanItsCopy(afonHome, projectStack, projectFile)
            && commands.stream().anyMatch(command -> !command.skipped() && command.stage() != ProjectStackPlan.StageId.REGISTER)) {
            String stale = String.format("The file %s of project stack %s is newer than its copy in the project stack's config, which the stacks deploy from: run "
                                         + "afon-cli.sh manage-project-stack configure %s first", projectFile, projectStack.id(), projectStack.id());
            if (!dryRun) {
                err.println(stale);
                return 1;
            }
            err.println("Warning: " + stale);
        }
        if (!dryRun) {
            try {
                ProjectStackHome.prepare(afonHome, projectStack);
                if (identityProjectStack != null) {
                    ProjectStackHome.prepare(afonHome, identityProjectStack);
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
                err.printf("Stage %s could not be prepared.  Resume with: manage-project-stack %s %s --from %s%n", stage, action, projectStack.id(), stage);
                return 1;
            }
            if (status != 0) {
                err.printf("Stage %s failed with exit status %d.  Resume with: manage-project-stack %s %s --from %s%n", stage, status, action,
                           projectStack.id(), failedStep == null ? stage : stage + ":" + failedStep);
                if (failedStep != null) {
                    err.printf("That resumes the stack from the step that failed, and does not do again the steps before it: --from %s would run them all, deleting the stack first%n", stage);
                }
                return 1;
            }
        }
        // what is left to run: a stage that is skipped is not
        Optional<Command> following = left.stream().filter(command -> !command.skipped()).findFirst();
        if (following.isPresent()) {
            String next = following.get().stage().name().toLowerCase(Locale.ROOT);
            out.printf("Stopped after stage %s, as asked.  Continue with: manage-project-stack %s %s --from %s%n",
                       commands.get(commands.size() - 1).stage().name().toLowerCase(Locale.ROOT), action, projectStack.id(), next);
            return 0;
        }
        if (action.equals("down")) {
            if (projectStack instanceof IdentityProjectStack) {
                out.println("Left as they are: the reserved egress IP, if the project stack names one, and the project stack's config and cdktf dirs.  The project stacks that"
                            + " used this identity are registered in it no more: bring it up again, then run their register stage");
            } else if (projectStack instanceof DataProjectStack) {
                out.println("Left as they are: the Data Provider's storage, with the data of its catalogs, the reserved egress IP, if the project stack names one, and the"
                            + " project stack's config and cdktf dirs");
            } else {
                out.println("Left as they are: the project stack's buckets, and its clients and group in the central identity, until it is closed:"
                            + " manage-project-stack run " + projectStack.id() + " manage-identity close");
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
        for (ProjectStackCommands.Invocation before : command.before()) {
            int status = runInvocation(cli, before, label + " (before)", dryRun);
            if (status == FAILED_TO_RUN) {
                return status;
            }
            if (status != 0) {
                err.printf("%s failed with exit status %d%n", String.join(" ", before.args()), status);
                return FAILED_BEFORE;
            }
        }
        for (ProjectStackCommands.Invocation run : command.run()) {
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
    private int runInvocation(String cli, ProjectStackCommands.Invocation invocation, String label, boolean dryRun) {
        List<String> argv = argv(cli, invocation.projectStack(), invocation.args());
        out.printf("%s: %s%n", label, String.join(" ", argv));
        if (dryRun) {
            return 0;
        }
        failedStep = null;
        // a stack's steps say which of them failed, in a file that they are given, so that what to resume from can be said
        List<String> args = invocation.args();
        boolean ofAStack = args.size() >= 3 && args.get(0).equals("manage-stack") && List.of("setup", "delete").contains(args.get(1));
        Path stepFile = null;
        try {
            if (ofAStack) {
                stepFile = Files.createTempFile("afon-failed-step", ".txt");
            }
            int status = stepFile == null ? executor.run(argv) : executor.run(argv, java.util.Map.of(StepRunner.FAILED_STEP_FILE, stepFile.toString()));
            if (status != 0 && stepFile != null) {
                String name = Files.readString(stepFile).strip();
                failedStep = name.isEmpty() ? null : name;
            }
            return status;
        } catch (IOException e) {
            err.printf("Cannot run %s: %s%n", cli, e.getMessage());
            return FAILED_TO_RUN;
        } finally {
            if (stepFile != null) {
                try {
                    Files.deleteIfExists(stepFile);
                } catch (IOException e) {
                    // a temp file that stays does no harm
                }
            }
        }
    }

    /** The step of a stack that the last command to fail was at, or {@code null} if it did not say. */
    private String failedStep;

    /**
     * @return the command line that runs a command of Afon in the home of a project stack: its home and its cdktf, which are those of the project stack
     */
    private List<String> argv(String cli, String projectStack, List<String> args) {
        List<String> argv = new ArrayList<>(List.of(cli, "--home", FileProjectStackProvider.projectDir(afonHome, projectStack).resolve(ProjectStackHome.HOME_DIR).toString(),
                                                    "--cdktf", afonHome.resolve(ProjectStackEnv.CDKTF_PREFIX + projectStack).toString()));
        argv.addAll(args);
        return argv;
    }

    /**
     * @return the identity project stack that the project stack names, or {@code null} if it names none: a TRE project stack and a data project stack use the central identity
     * @throws ProjectStackException      if the identity project stack's file is not valid
     * @throws IllegalStateException if the project stack it names is not an identity project stack, or has no file
     */
    private IdentityProjectStack identityProjectStackOf(ProjectStack projectStack) {
        String id = projectStack.identityProjectStack();
        if (id == null) {
            return null;
        }
        FileProjectStackProvider provider = FileProjectStackProvider.forProjectStack(afonHome, id);
        if (!provider.exists()) {
            throw new IllegalStateException(String.format("Project stack %s names the identity project stack %s, which has no file %s", projectStack.id(), id,
                                                          provider.file()));
        }
        if (!(provider.getProjectStack() instanceof IdentityProjectStack identity)) {
            throw new IllegalStateException(String.format("Project stack %s names %s as its identity project stack, but %s has the role %s", projectStack.id(), id,
                                                          id, provider.getProjectStack().role()));
        }
        // the identity may be on another cloud than the project stack, eg a Data Provider on Azure that uses an identity on AWS: its kubeconfig is written for
        // its own cloud, and the port-forward to its Keycloak checks the context against KEYCLOAK_CLOUD
        return identity;
    }

    /**
     * Runs a command of Afon in the home of the project stack, as {@code afon-cli.sh --home <its home> <command>} does, so that it uses its config,
     * its address and its cluster, whatever env is active.  The args are the command's as they are, none is for this one.
     *
     * @return the exit status of the command
     */
    private int runInProject(String[] args) {
        if (args.length < 3) {
            return usage("run takes a <project-stack> and a command of Afon to run in its home");
        }
        FileProjectStackProvider provider;
        try {
            provider = FileProjectStackProvider.forProjectStack(afonHome, args[1]);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project stack file %s: write the project stack's %s in its dir%n", provider.file(), FileProjectStackProvider.FILE);
            return 1;
        }
        ProjectStack projectStack;
        try {
            projectStack = provider.getProjectStack();
            ProjectStackHome.prepare(afonHome, projectStack);
        } catch (ProjectStackException | IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
        List<String> command = new ArrayList<>(List.of(args).subList(2, args.length));
        List<String> argv = argv(Path.of(cliDir.get(), "afon-cli.sh").toString(), args[1], command);
        out.println(String.join(" ", argv));
        try {
            // the cloud of the project stack, which the launcher takes from AFON_CLOUD, as it takes --cloud, which the launcher of this command has taken
            // from the command's args, so that the commands that need a cloud, eg cdktf-store, need not be given it
            // and its id, AFON_PROJECT_STACK, from which the commands that make something of its own, eg its egress IP, name it, whether or not it has been configured yet
            int status = executor.run(argv, java.util.Map.of("AFON_CLOUD", projectStack.cloud().toString().toLowerCase(Locale.ROOT), "AFON_PROJECT_STACK", args[1]));
            if (status != 0) {
                err.printf("%s failed with exit status %d%n", command.get(0), status);
            }
            return status;
        } catch (IOException e) {
            err.printf("Cannot run %s: %s%n", argv.get(0), e.getMessage());
            return 1;
        }
    }

    /**
     * Reserves, or shows, the egress IP of the project stack, with the command of its cloud, {@code <cloud>-egress-ip}, as its name is that of the project stack, so that no two
     * project stacks share an address.  The file asks for it, so it is the step after {@code init}: the file is not valid, and the project stack cannot be configured, until it has the
     * address, so nothing is read of the file but its cloud and the env it is made from, and the command runs in a home of its own, with the config of that env, see
     * {@link ProjectStackHome#prepareForEnv}.
     */
    private int egressIp(String[] args) {
        List<String> positional = new ArrayList<>();
        String env = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(ENV) && i + 1 < args.length) {
                env = args[++i];
            } else if (args[i].startsWith("--")) {
                return usage("Unknown option, or no value for " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() != 2 || !List.of("create", "show", "delete").contains(positional.get(0))) {
            return usage("egress-ip takes create, show or delete and a <project-stack>");
        }
        String action = positional.get(0);
        String id = positional.get(1);
        FileProjectStackProvider provider;
        try {
            provider = FileProjectStackProvider.forProjectStack(afonHome, id);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project stack file %s: write it with manage-project-stack init %s <cloud>%n", provider.file(), id);
            return 1;
        }
        java.util.Map<String, Object> section;
        try {
            section = FileProjectStackProvider.projectStackSection(Files.readString(provider.file(), StandardCharsets.UTF_8));
        } catch (ProjectStackException | IOException e) {
            err.println(e.getMessage());
            return 1;
        }
        AfonCloud cloud;
        try {
            cloud = AfonCloud.valueOf(String.valueOf(section.get("cloud")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            err.printf("%s has no cloud, aws, azure or gcp, in its projectStack section: it is that of the project stack, which init writes%n", provider.file());
            return 1;
        }
        String fileEnv = section.get("env") == null ? null : String.valueOf(section.get("env")).trim();
        if (env == null && fileEnv != null && !fileEnv.isEmpty() && fileEnv.startsWith("<")) {
            err.printf("The env of %s is still %s: give the env whose config has the properties of the cloud, such as its region, with %s <env>, or set it in the file%n", provider.file(),
                       fileEnv, ENV);
            return 1;
        }
        String chosen = env != null ? env : fileEnv != null && !fileEnv.isEmpty() ? fileEnv : null;
        Path envConfig = chosen == null ? afonHome.resolve("config") : afonHome.resolve(ProjectStackEnv.CONFIG_PREFIX + chosen);
        if (!Files.isDirectory(envConfig)) {
            err.printf("No config %s: %s%n", envConfig, chosen == null ? "there is no active config" : "the env " + chosen + " has none");
            return 1;
        }
        Path home;
        try {
            home = ProjectStackHome.prepareForEnv(afonHome, id, envConfig);
        } catch (UncheckedIOException e) {
            err.println(e.getMessage());
            return 1;
        }
        String lower = cloud.toString().toLowerCase(Locale.ROOT);
        List<String> argv = List.of(Path.of(cliDir.get(), "afon-cli.sh").toString(), "--home", home.toString(), lower + "-egress-ip", action, "afon-egress-ip-" + id);
        out.println(String.join(" ", argv));
        try {
            int status = executor.run(argv, java.util.Map.of("AFON_CLOUD", lower, "AFON_PROJECT_STACK", id));
            if (status != 0) {
                err.printf("%s-egress-ip failed with exit status %d%n", lower, status);
            }
            return status;
        } catch (IOException e) {
            err.printf("Cannot run %s: %s%n", argv.get(0), e.getMessage());
            return 1;
        }
    }

    private void print(ProjectStackPlan plan) {
        out.printf("Project stack %s on %s, with the role %s%n", plan.projectStack().id(), plan.projectStack().cloud(), plan.projectStack().role());
        out.println("Up, in order (down is the reverse):");
        int number = 1;
        for (ProjectStackPlan.Stage stage : plan.stages()) {
            out.printf("  %d. %s%s%s%n", number++, describe(plan.projectStack(), stage),
                       stage.stateKey() == null ? "" : ", state " + stage.stateKey(),
                       stage.nightly() ? ", deleted and set up again nightly" : "");
        }
    }

    /**
     * @return the stage as the plan names it: what a stage of a stack deploys, or for the registration, which has no stack, what it makes and where, in the
     * central identity of the project stack: its clients, its group and its members
     */
    private static String describe(ProjectStack projectStack, ProjectStackPlan.Stage stage) {
        if (stage.id() != StageId.REGISTER || !(projectStack instanceof TreProjectStack tre)) {
            return stage.toString();
        }
        String where = tre.identityProjectStack() != null ? tre.identityProjectStack() : "at " + tre.identity().issuerUrl();
        String clients = tre.identity().gitClient() == null ? "the client " + tre.identity().treClient()
                                                              : "the clients " + tre.identity().treClient() + " and " + tre.identity().gitClient();
        int members = tre.members().size();
        return String.format("register, in the central identity (%s): %s, the group %s and its %d member%s", where, clients, tre.id(), members,
                             members == 1 ? "" : "s");
    }

    public static void main(String[] args) {
        int status = new ManageProjectStackMain(System.out, System.err, ProjectStackEnv.defaultAfonHome(), new ProcessExecutor(), ScriptMain::afonCliDir,
                                           new ProjectStackCommands.Stacks(
                                               stack -> StackDefinitions.find(stack).isPresent(),
                                               stack -> StackDefinitions.find(stack).map(d -> d.declares(GitComponent.class)).orElse(false))).run(args);
        if (status != 0) {
            System.exit(status);
        }
    }
}
