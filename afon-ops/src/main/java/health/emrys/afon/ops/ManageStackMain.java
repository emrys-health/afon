package health.emrys.afon.ops;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.project.ProjectStackUserProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.docs.DocumentNames;
import health.emrys.afon.ops.docs.Documenter;
import health.emrys.afon.ops.flow.FlowPlanFactory;
import health.emrys.afon.ops.flow.FlowRunner;
import health.emrys.afon.ops.flow.FlowStep;
import health.emrys.afon.ops.flow.StackFlow;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.help.CommandHelp;
import health.emrys.afon.ops.stack.ValidateStackMain;
import health.emrys.afon.ops.synth.PlanSnapshots;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.StepActions;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages a stack, by running the steps of an action on it, as provided by {@link StackPlanFactory}:
 * <pre>
 * manage-stack &lt;action&gt; &lt;stack-id&gt; [&lt;step&gt;] [--replace]
 * manage-stack run &lt;stack-id&gt; [&lt;scenario&gt; [&lt;step&gt;]]
 * manage-stack validate [&lt;stack-id&gt; [--runs &lt;n&gt;] | --export-stacks]
 * manage-stack snapshot &lt;dir&gt; [&lt;stack-id&gt;...]
 * manage-stack compare &lt;before-dir&gt; &lt;after-dir&gt;
 * manage-stack run [&lt;flow&gt; [&lt;item&gt;:&lt;step&gt;]] [--replace] [--dry-run]
 * </pre>
 * <ul>
 *   <li>{@code <action>} is one of {@code delete}, {@code deploy} or {@code setup}, see {@link StackAction};</li>
 *   <li>{@code <stack-id>} is {@code <cloud>-<stack-type>}, see {@link StackId},
 *       or {@code <cloud>-all}, to delete every stack of the cloud;</li>
 *   <li>{@code <step>}, if given, is the step from which to resume, as for the other stack commands;</li>
 *   <li>{@code --replace}, if given, lets {@code deploy} and {@code setup} delete the other stacks conflicting with the
 *       stack, else they fail, naming them, before deleting any.</li>
 * </ul>
 * {@code validate} validates the config of a stack by synthesising its plan, without deploying it: it is {@link ValidateStackMain}, the command {@code validate-stack}, with the
 * args that follow {@code validate}, so that it is there among the actions on a stack, as {@code validate} is among those of a project stack.
 * {@code snapshot} copies the plans of the stacks that were synthesised, eg by {@code validate}, to a dir, to compare them with those of another time, eg before and
 * after a refactoring, and {@code compare} compares the plans of the stacks in two such dirs, or in the stacks dir, listing the resources that were added and removed, which tofu
 * creates and destroys, then the values that changed, and exiting with 1 if they differ: see {@link PlanSnapshots}.
 * <p>
 * {@code run} runs a scenario on a stack that is set up, as provided by its components, eg Snowflake reading a catalog, and without
 * a scenario lists those of the stack.  It also runs a {@link StackFlow}, a composed run: a named, ordered list of actions
 * and scenarios, stopping at the first failure, which reports the {@code <item>:<step>} to resume from.  The first arg
 * says which: a flow name, see {@link StackFlows}, which is never a stack id, or else a stack id.  Without args it lists
 * the flows.  {@code --replace} applies to every item of a flow, and {@code --dry-run} lists its steps without running them.
 * <p>
 * Stacks run side by side unless they conflict, so deploying or setting up a stack first deletes the stacks it
 * conflicts with, while deleting a stack deletes just that stack, see {@link StackPlanFactory}.
 */
public class ManageStackMain {

    private static final Logger logger = getLogger(ManageStackMain.class);

    /**
     * The option letting deploy and setup delete the other stacks conflicting with the stack.
     */
    static final String REPLACE = "--replace";

    /**
     * The option listing the steps of a flow without running them.
     */
    static final String DRY_RUN = "--dry-run";

    /**
     * The option, only to {@code run}, exporting the bundled flows to the config dir.
     */
    static final String EXPORT_FLOWS = "--export-flows";

    private final StackPlanFactory factory;
    private final AfonUserProvider users;

    ManageStackMain(StackPlanFactory factory, AfonUserProvider users) {
        this.factory = factory;
        this.users = users;
    }

    /**
     * @return the steps provider of the action on the stack, or {@code null} if the action or stack is unknown,
     * or the action is not supported for the stack
     */
    StepsProvider stepsProviderFor(String action, String stackId) {
        return stepsProviderFor(action, stackId, false);
    }

    /**
     * @return the steps provider of the action on the stack, replacing the other conflicting stacks or not, or
     * {@code null} if the action or stack is unknown, or the action is not supported for the stack
     */
    StepsProvider stepsProviderFor(String action, String stackId, boolean replace) {
        return stepsProviderFor(StackAction.of(action), parseStackId(stackId), replace);
    }

    /**
     * @param action  the action, or {@code null} if it is unknown
     * @param stackId the stack, or {@code null} if it is not a stack id
     * @return the steps provider of the action on the stack, or {@code null} if either is {@code null}, or the stack is not
     * defined, or the action is not supported for it
     */
    private StepsProvider stepsProviderFor(StackAction action, StackId stackId, boolean replace) {
        if (action == null || stackId == null) {
            return null;
        }
        try {
            return factory.stepsProviderFor(action, stackId, replace);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return the id of the stack named by the text, as an id or as {@code <cloud>-<role>}, see {@link StackDefinitions#resolve},
     * or {@code null} if it is neither
     */
    private static StackId parseStackId(String text) {
        try {
            return StackDefinitions.resolve(StackId.parse(text));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return the steps provider of the scenario of the stack, or {@code null} if the stack is unknown, or has no such scenario
     */
    StepsProvider runProviderFor(String stackId, String scenario, AfonPropertyProvider properties) {
        try {
            return factory.scenarios(StackDefinitions.resolve(StackId.parse(stackId)), properties).get(scenario);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void printScenarios(String stackId, AfonPropertyProvider properties) {
        Map<String, StepsProvider> scenarios;
        try {
            scenarios = factory.scenarios(StackDefinitions.resolve(StackId.parse(stackId)), properties);
        } catch (IllegalArgumentException e) {
            scenarios = Map.of();
        }
        System.out.printf("Scenarios of stack '%s': %s%n", stackId, scenarios.isEmpty() ? "none" : String.join(", ", scenarios.keySet()));
    }

    private void printUsage() {
        System.out.println("Usage: manage-stack <action> <stack-id> [<step>] [" + REPLACE + "], where:");
        System.out.println("  <action> is one of: " + StackAction.derivingSteps().stream()
                                                             .map(StackAction::toString)
                                                             .collect(Collectors.joining(", ")));
        System.out.println("  <stack-id> is one of, with its supported actions:");
        factory.supportedActions().forEach((stackId, actions) -> System.out.printf(
            "    %s: %s%n", stackId, actions.stream().map(StackAction::toString).collect(Collectors.joining(", "))));
        System.out.println("    or <cloud>-<role>, the stack of the cloud with the role catalog, identity or tre, eg aws-tre");
        System.out.println("  <step>, if given, is the step from which to resume");
        System.out.println("Usage: manage-stack validate [<stack-id> [--runs <n>] | --export-stacks], which validates the config of a stack by synthesising its plan,");
        System.out.println("  and lists the stacks if none is given");
        System.out.println("Usage: manage-stack snapshot <dir> [<stack-id>...], which copies the plans of the stacks that were synthesised to a dir, to compare them with those of another time");
        System.out.println("Usage: manage-stack compare <before-dir> <after-dir>, which compares the plans of the stacks in two snapshots of them, and exits with 1 if they differ");
        System.out.println("Usage: manage-stack run <stack-id> [<scenario> [<step>]], which runs a scenario on a stack that is set up,");
        System.out.println("  or lists the scenarios of the stack, if none is given");
        printStackScenarios(new OpsPropertyProvider());
        System.out.println("Usage: manage-stack run [<flow> [<item>:<step>]] [" + DRY_RUN + "], which runs a flow of actions and scenarios,");
        System.out.println("  resuming from the step, or lists the flows if none is given; " + DRY_RUN + " lists its steps without running them");
        System.out.println("  " + REPLACE + ", if given, lets deploy and setup delete the other stacks conflicting with the stack,");
        System.out.println("    else they fail, naming them, before deleting any");
        System.out.println("Usage: manage-stack run " + EXPORT_FLOWS + ", which writes the bundled flows, stack-flows.yaml, to the config dir,");
        System.out.println("  to define the flows differently: the file there replaces the bundled one");
    }

    /**
     * Prints the generated doc of the args, eg {@code setup <stack-id>}, else the usage.
     */
    private void help(String[] args) {
        Optional<String> doc = DocumentNames.forArgs(args).flatMap(this::helpDoc);
        if (doc.isPresent()) {
            System.out.println(doc.get());
            return;
        }
        printUsage();
    }

    /**
     * @return the doc generated now from the config, so that its steps are those that are run, else the one bundled in the jar, which
     * was generated from the config when it was packaged, if it could not be
     */
    private Optional<String> helpDoc(String filename) {
        try {
            Optional<String> generated = Documenter.generate(filename, new OpsPropertyProvider(), users);
            if (generated.isPresent()) {
                return generated;
            }
        } catch (RuntimeException e) {
            logger.warn("Could not generate {} from the config, showing the one bundled: {}", filename, e.toString());
        }
        return CommandHelp.findGeneratedDoc(filename);
    }

    /**
     * The action that validates a stack, which is the command {@code validate-stack}.
     */
    static final String VALIDATE = "validate";

    /**
     * The action that compares the plans of the stacks in two dirs.
     */
    static final String COMPARE = "compare";

    /**
     * The action that copies the plans of the stacks that were synthesised to a dir.
     */
    static final String SNAPSHOT = "snapshot";

    public static void main(String[] allArgs) {
        if (allArgs.length > 0 && VALIDATE.equals(allArgs[0])) {
            validate(Arrays.copyOfRange(allArgs, 1, allArgs.length));
            return;
        }
        if (allArgs.length > 0 && SNAPSHOT.equals(allArgs[0])) {
            snapshot(Arrays.copyOfRange(allArgs, 1, allArgs.length));
            return;
        }
        if (allArgs.length > 0 && COMPARE.equals(allArgs[0])) {
            compare(Arrays.copyOfRange(allArgs, 1, allArgs.length));
            return;
        }
        if (allArgs.length == 2 && StackAction.of(allArgs[0]) == StackAction.RUN && EXPORT_FLOWS.equals(allArgs[1])) {
            // before loading the flows, as it is how to replace a file that is not valid
            exportFlows();
            return;
        }
        try {
            StackDefinitions.checkLoads();
            StackFlows.checkLoads();
        } catch (IllegalStateException e) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        ManageStackMain manager = new ManageStackMain(new StackPlanFactory(), ProjectStackUserProvider.forActiveConfig());
        if (Arrays.stream(allArgs).anyMatch(arg -> arg.equals("-h") || arg.equals("--help"))) {
            manager.help(Arrays.stream(allArgs).filter(arg -> !arg.equals("-h") && !arg.equals("--help"))
                               .toArray(String[]::new));
            return;
        }
        boolean replace = Arrays.asList(allArgs).contains(REPLACE);
        boolean dryRun = Arrays.asList(allArgs).contains(DRY_RUN);
        String[] args = Arrays.stream(allArgs).filter(arg -> !arg.equals(REPLACE) && !arg.equals(DRY_RUN))
                              .toArray(String[]::new);
        if (args.length > 0 && StackAction.of(args[0]) == StackAction.RUN) {
            manager.run(args, replace, dryRun);
            return;
        }
        if (args.length < 2) {
            manager.printUsage();
            return;
        }
        manager.stack(args, replace);
    }

    /**
     * Validates a stack as {@code validate-stack} does, with the args that follow {@code validate}: before loading the flows, as it is how to replace a file of stacks that is
     * not valid, as for {@code validate-stack} itself.  Its help is that of {@code validate-stack}.
     */
    private static void validate(String[] args) {
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("-h") || arg.equals("--help"))) {
            System.out.println(CommandHelp.find("validate-stack").orElse("Usage: manage-stack validate [<stack-id> [--runs <n>] | --export-stacks]"));
            return;
        }
        ValidateStackMain.main(args);
    }

    /**
     * @return the id of the stack named by the arg, as an id or as {@code <cloud>-<role>}, see {@link StackDefinitions#resolve}, or the arg as it is if it is neither, eg the name
     * of a dir of the stacks dir that is not a stack of the stacks file
     */
    static String named(String arg) {
        StackId stackId = parseStackId(arg);
        return stackId == null ? arg : stackId.toString();
    }

    /**
     * Copies to {@code <dir>/<stack-id>/cdk.tf.json} the plan of each stack, or of those given, that was synthesised, eg by {@code validate}, in {@link ConfigUtils#stacksDir()}: it
     * copies nothing but those files.  It needs no source tree or git, so it runs the same on a deployed machine.
     */
    private static void snapshot(String[] args) {
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("-h") || arg.equals("--help"))) {
            System.out.println(CommandHelp.findAction("manage-stack", "snapshot").orElse("Usage: manage-stack snapshot <dir> [<stack-id>...]"));
            return;
        }
        if (args.length < 1) {
            System.out.println("Usage: manage-stack snapshot <dir> [<stack-id|role>...]");
            System.exit(1);
            return;
        }
        Path stacksDir = Path.of(ConfigUtils.stacksDir());
        List<String> stacks;
        try {
            stacks = PlanSnapshots.snapshot(stacksDir, Path.of(args[0]), Arrays.stream(args).skip(1).map(ManageStackMain::named).toList());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + ", synthesise it first, eg with manage-stack validate");
            System.exit(1);
            return;
        } catch (java.io.IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        if (stacks.isEmpty()) {
            System.err.println("No stack synthesised in " + stacksDir + ", synthesise it first, eg with manage-stack validate");
            System.exit(1);
            return;
        }
        System.out.printf("Snapshot of %d stacks from %s written to %s: %s%n", stacks.size(), stacksDir, args[0], String.join(", ", stacks));
    }

    /**
     * Compares the plans of the stacks in two dirs, each a snapshot, or the stacks dir: it lists the resources that were added and removed, which tofu creates and destroys (a
     * renamed one is both), then the values that changed, with the long ones reduced to their hash, and exits with 1 if the plans differ.
     */
    private static void compare(String[] args) {
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("-h") || arg.equals("--help"))) {
            System.out.println(CommandHelp.findAction("manage-stack", "compare").orElse("Usage: manage-stack compare <before-dir> <after-dir>"));
            return;
        }
        if (args.length != 2) {
            System.out.println("Usage: manage-stack compare <before-dir> <after-dir>");
            System.exit(1);
            return;
        }
        PlanSnapshots.Comparison comparison;
        try {
            comparison = PlanSnapshots.compare(Path.of(args[0]), Path.of(args[1]));
        } catch (IllegalArgumentException | java.io.IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        comparison.lines().forEach(System.out::println);
        if (comparison.differs()) {
            System.exit(1);
        }
    }

    private static void exportFlows() {
        try {
            Path file = StackFlows.exportBundled(Path.of(ConfigUtils.configDir()));
            System.out.printf("Exported the bundled flows to %s, which now replaces them: edit it to define the flows differently%n", file);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private void stack(String[] args, boolean replace) {
        // parsed once, here at the edge
        StackAction action = StackAction.of(args[0]);
        StackId stackId = parseStackId(args[1]);
        StepsProvider stepsProvider = stepsProviderFor(action, stackId, replace);
        if (stepsProvider == null) {
            System.out.printf("Unsupported action '%s' on stack '%s'%n", args[0], args[1]);
            printUsage();
            System.exit(1);
            return;
        }
        if (action == StackAction.DELETE && stackId.isAll()) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            String warning = String.format("Deleting %s deletes every stack deployed in cloud %s", stackId, stackId.cloud());
            System.out.println(warning);
            logger.warn(warning);
        }
        // the steps acting on its cluster use the kubeconfig of the stack, not whichever the machine's current context names
        ConfigUtils.useStack(stackId);
        logger.info("Running {} on stack {}{}", action, stackId, replace ? ", replacing the conflicting stacks" : "");
        new StepRunner(users).runUsing(stepsProvider, Arrays.copyOfRange(args, 2, args.length));
    }

    private void run(String[] args, boolean replace, boolean dryRun) {
        if (args.length < 2) {
            printRuns();
            return;
        }
        if (isFlow(args[1])) {
            runFlow(args, replace, dryRun);
            return;
        }
        AfonPropertyProvider properties = new OpsPropertyProvider();
        if (args.length < 3) {
            printScenarios(args[1], properties);
            return;
        }
        runScenario(args, properties);
    }

    private void printStackScenarios(AfonPropertyProvider properties) {
        factory.supportedActions().keySet().stream().filter(stackId -> !stackId.isAll())
               .filter(stackId -> !factory.scenarios(stackId, properties).isEmpty())
               .forEach(stackId -> printScenarios(stackId.toString(), properties));
    }

    /**
     * Prints what {@code run} can run: the flows, and the stacks with their scenarios.
     */
    private void printRuns() {
        printFlows();
        System.out.println("Stacks with scenarios, to run with: manage-stack run <stack-id> <scenario>");
        printStackScenarios(new OpsPropertyProvider());
        System.out.println("To define the flows differently: manage-stack run " + EXPORT_FLOWS);
    }

    private void printFlows() {
        System.out.println("Flows:");
        StackFlows.all().forEach(flow -> System.out.printf("  %s: %s%n", flow.name(),
            flow.items().stream().map(Object::toString).collect(Collectors.joining(", "))));
    }

    /**
     * @return whether the name is that of a flow, rather than a stack id, even if the flow cannot run for want of a stack
     */
    boolean isFlow(String name) {
        return StackFlows.isDefined(name);
    }

    private void runFlow(String[] args, boolean replace, boolean dryRun) {
        AfonPropertyProvider properties = new OpsPropertyProvider();
        List<FlowStep> steps;
        try {
            StackFlow flow = StackFlows.get(args[1], factory, properties);
            steps = new FlowPlanFactory(factory).steps(flow, new StepOperations(new StepActions(), properties, users), replace);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            printFlows();
            System.exit(1);
            return;
        }
        if (dryRun) {
            int current = 0;
            for (FlowStep step : steps) {
                if (step.itemIndex() != current) {
                    current = step.itemIndex();
                    System.out.printf("%d. %s%n", current, step.item());
                }
                System.out.printf("     %s%n", step.qualifiedName());
            }
            return;
        }
        logger.info("Running flow {}{}", args[1], replace ? ", replacing the conflicting stacks" : "");
        try {
            new FlowRunner().run(args[1], steps, args.length > 2 ? args[2] : null);
        } catch (FlowRunner.FlowFailedException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
    }

    private void runScenario(String[] args, AfonPropertyProvider properties) {
        StepsProvider stepsProvider = runProviderFor(args[1], args[2], properties);
        if (stepsProvider == null) {
            System.out.printf("Unsupported scenario '%s' on stack '%s'%n", args[2], args[1]);
            printScenarios(args[1], properties);
            System.exit(1);
            return;
        }
        ConfigUtils.useStack(parseStackId(args[1]));
        logger.info("Running scenario {} on stack {}", args[2], args[1]);
        new StepRunner(users).runUsing(stepsProvider, Arrays.copyOfRange(args, 3, args.length));
    }


}
