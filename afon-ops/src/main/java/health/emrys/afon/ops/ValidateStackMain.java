package health.emrys.afon.ops;

import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.synth.DeterministicSynthesis;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Validates the config of a stack, via {@link CloudStackValidator}, by synthesising its plan from the config,
 * without deploying it.
 * The stack is given by its id, {@code <cloud>-<stack-type>}, as defined in
 * {@link health.emrys.afon.cdk.StackDefinitions}.  With no stack, it lists the stacks.
 * <p>
 * It also checks that the plan is the same in each JVM, see {@link DeterministicSynthesis}: it synthesises the stack in
 * {@code --runs} JVMs (by default {@link #DEFAULT_RUNS}) of their own, each of which is this command with {@link #SINGLE_RUN_PROPERTY}
 * set, and fails if the plans differ.  With {@code --runs 1} it synthesises once, in this JVM, and checks nothing else.
 */
public class ValidateStackMain {

    /**
     * System property set in the JVMs that synthesise once, rather than check the plan is deterministic
     */
    static final String SINGLE_RUN_PROPERTY = "afon.validate.single";
    static final int DEFAULT_RUNS = 3;
    private static final String RUNS_OPTION = "--runs";
    static final String EXPORT_OPTION = "--export-stacks";
    private static final int RUN_TIMEOUT_MINUTES = 15;

    /**
     * @param stack the id of the stack, as given
     * @param runs  the number of times to synthesise it, at least 1
     */
    record Arguments(String stack, int runs) {

        /**
         * @param args {@code <stack-id> [--runs <n>]}
         * @return the arguments, or {@code null} if there are none
         * @throws IllegalArgumentException if they are not as above
         */
        static Arguments parse(String[] args) {
            if (args.length == 0) {
                return null;
            }
            int runs = DEFAULT_RUNS;
            if (args.length == 3 && RUNS_OPTION.equals(args[1])) {
                try {
                    runs = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    runs = 0;
                }
                if (runs < 1) {
                    throw new IllegalArgumentException(RUNS_OPTION + " must be a number of at least 1, not " + args[2]);
                }
            } else if (args.length != 1) {
                throw new IllegalArgumentException("Usage: validate-stack <stack> [" + RUNS_OPTION + " <n>] | validate-stack " + EXPORT_OPTION);
            }
            return new Arguments(args[0], runs);
        }
    }

    public static void main(String[] args) {
        if (args.length == 1 && EXPORT_OPTION.equals(args[0])) {
            // before loading the stacks, as it is how to replace a file that is not valid
            exportStacks();
            return;
        }
        try {
            StackDefinitions.checkLoads();
        } catch (IllegalStateException e) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        CloudStackValidator validator = new CloudStackValidator();
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        if (arguments == null) {
            printUsage(validator);
            return;
        }
        StackId stack = validator.resolve(arguments.stack());
        if (stack == null) {
            System.out.println("Stack not found: " + arguments.stack());
            printUsage(validator);
            System.exit(1);
            return;
        }
        AfonPropertyProvider properties = new OpsPropertyProvider();
        if (System.getProperty(SINGLE_RUN_PROPERTY) == null) {
            // before validating, as what the stack is made of helps to see why it fails; not in the JVMs of the further runs
            try {
                System.out.println(describe(stack, properties));
            } catch (RuntimeException e) {
                System.err.printf("Failed to validate stack %s: %s%n", stack, e);
                System.exit(1);
                return;
            }
        }
        if (System.getProperty(SINGLE_RUN_PROPERTY) == null && arguments.runs() > 1) {
            checkDeterministic(validator, stack, arguments.runs());
            return;
        }
        String stackDir;
        try {
            stackDir = validator.validate(stack, properties);
        } catch (RuntimeException e) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            System.err.printf("Failed to validate stack %s: %s%n", stack, e);
            System.exit(1);
            return;
        }
        System.out.printf("Validated stack %s by synthesising it into %s%n", stack, stackDir);
    }

    private static void exportStacks() {
        try {
            Path file = StackDefinitions.exportBundled(Path.of(ConfigUtils.configDir()));
            System.out.printf("Exported the bundled stacks to %s, which now replaces them: edit it to define the stacks differently%n", file);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * @return the role of the stack, if it has one, and its components, as defined in the stacks file, in the order they are
     * deployed, for the config: those whose {@code when} or {@code unless} property does not apply are left out
     */
    static String describe(StackId stack, AfonPropertyProvider properties) {
        StackDefinition definition = StackDefinitions.get(stack);
        List<String> names = definition.componentsFor(properties).stream()
            .map(component -> component.getClass().getSimpleName().replaceFirst("Component$", "")).toList();
        String role = definition.role() == null ? "no role" : "role '" + definition.role() + "'";
        return String.format("Stack '%s' of %s has %s and components: %s",
                             stack, StackDefinitions.source(), role, String.join(", ", names));
    }

    private static void checkDeterministic(CloudStackValidator validator, StackId stack, int runs) {
        List<String> differences;
        try {
            differences = DeterministicSynthesis.check(stack, Path.of(ConfigUtils.stacksDir()), runs,
                                                       ValidateStackMain::synthesiseInNewJvm);
        } catch (IOException | RuntimeException e) {
            System.err.printf("Failed to validate stack %s: %s%n", stack, e.getMessage());
            System.exit(1);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
            return;
        }
        if (!differences.isEmpty()) {
            // the paths only, as the values are those of the config
            System.err.printf("Stack %s does not synthesise to the same plan in each JVM, so tofu would replace what differs on every deploy:%n", stack);
            differences.forEach(System.err::println);
            System.exit(1);
            return;
        }
        System.out.printf("Validated stack %s by synthesising it into %s, to the same plan in each of %d JVMs%n",
                          stack, validator.stackDir(stack), runs);
    }

    /**
     * Runs this command in a JVM of its own with the same flags, ie the system properties and the config, as this one, to synthesise once.
     */
    private static void synthesiseInNewJvm(StackId stack) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-D" + SINGLE_RUN_PROPERTY + "=true");
        String classPath = System.getProperty("java.class.path");
        if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
            // run from the CLI jar, which finds the command by its name
            command.addAll(List.of("-jar", classPath, "validate-stack"));
        } else {
            command.addAll(List.of("-cp", classPath, ValidateStackMain.class.getName()));
        }
        // the id as the command takes it
        command.add(stack.toString());
        Path log = Files.createTempFile("afon-validate-stack", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Synthesis did not finish in " + RUN_TIMEOUT_MINUTES + " minutes");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Synthesis failed with exit " + process.exitValue() + ":\n" + Files.readString(log));
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static void printUsage(CloudStackValidator validator) {
        System.out.println("Usage: validate-stack <stack> [" + RUNS_OPTION + " <n>] | validate-stack " + EXPORT_OPTION + ", where <stack> is one of:");
        validator.stackNames().forEach(name -> System.out.println("  " + name));
    }

}
