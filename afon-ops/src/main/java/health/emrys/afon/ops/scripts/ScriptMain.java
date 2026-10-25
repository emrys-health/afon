package health.emrys.afon.ops.scripts;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.ConfigUtils.afonCkdtf;
import static health.emrys.afon.config.ConfigUtils.afonCloud;
import static health.emrys.afon.config.ConfigUtils.afonHome;
import static java.lang.String.format;

/**
 * Runs one of the scripts in the cli distribution, as an alternative to using
 * {@code afon-cli-scripts.sh} directly.  Mirrors what {@code afon.sh} does for the same
 * {@code --flag}s: translates them into the {@code AFON_*} env vars that {@code afon-cli-scripts.sh}
 * and the scripts themselves expect, and forwards whatever args are left over as positional args
 * to the script.
 * <p>
 * This is the template the generated {@code *Main} script command classes under
 * {@code health.emrys.afon.ops.scripts.<cloud>} delegate to (see {@code GenerateScriptMains.java}
 * and the {@code generate-sources} binding in afon-ops/pom.xml) - it deliberately has no
 * {@code main} method of its own, so it is not itself picked up as a command.
 */
public class ScriptMain {

    private final String scriptPath;
    private String stackCloud;
    private String servingComponent;

    public ScriptMain(String scriptPath) {
        this.scriptPath = scriptPath;
    }

    /**
     * For a script that takes a stack, given as its id or its role in the cloud, eg {@code tre}: the stack is found in the stacks
     * file before the script runs, and what it needs is given to the script as {@code AFON_STACK_*} env vars, see
     * {@link StackNeeds#env()}, so that the script need not know the stacks.  The argument given to the script is the id.
     * (A script run by {@code afon.sh}, which has no JVM, asks the ops command {@code stack-needs} for the same, see
     * {@code scripts/stack/needs-source.sh}.)
     *
     * @param cloud the cloud of the script, eg {@code aws}
     * @return this
     */
    public ScriptMain takingStackOf(String cloud) {
        this.stackCloud = cloud;
        return this;
    }

    /**
     * For a script that checks the stack serving a kind of component, eg Polaris, which it is given, or else finds among the
     * running stacks that can serve it: the script is also given them, as {@code AFON_STACK_CANDIDATES}, the ids separated by
     * spaces, when it is not given a stack.  Needs {@link #takingStackOf}.
     *
     * @param component the name of the component, as in the stacks file, eg {@code Polaris}
     * @return this
     */
    public ScriptMain servingComponent(String component) {
        this.servingComponent = component;
        return this;
    }

    /**
     * Finds the stack among the args of the script, ie the first that is not a boolean flag such as {@code true}, as
     * {@code azure-create [true|false] <stack>} takes them in any order, and replaces it with the id of the stack.
     *
     * @param env receives what the stack needs
     * @return the args, with the stack as an id, or unchanged if none of them is a stack, which the script reports
     * @throws IllegalArgumentException if it is not a stack of the cloud that the stacks file defines
     */
    static List<String> withStackResolved(AfonCloud cloud, List<String> args, AfonPropertyProvider properties,
                                          Map<String, String> env) {
        return withStackResolved(cloud, args, properties, env, null);
    }

    /**
     * As above and, if a component is given and none of the args is a stack, gives the stacks that serve it in
     * {@code AFON_STACK_CANDIDATES}, see {@link #servingComponent}.
     */
    static List<String> withStackResolved(AfonCloud cloud, List<String> args, AfonPropertyProvider properties,
                                          Map<String, String> env, String component) {
        List<String> resolved = new ArrayList<>(args);
        for (int i = 0; i < resolved.size(); i++) {
            String arg = resolved.get(i);
            if (arg.equals("true") || arg.equals("false")) {
                continue;
            }
            StackNeeds needs = StackNeeds.resolve(cloud, arg, properties);
            resolved.set(i, needs.id().toString());
            env.putAll(needs.env());
            return resolved;
        }
        env.putAll(StackNeeds.env(cloud, null, component, properties));
        return resolved;
    }

    public void run(String... args) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AFON_CLI_DIR", afonCliDir());
        env.put("AFON_HOME", afonHome());
        env.put("AFON_CDKTF", afonCkdtf());
        env.put("AFON_CLOUD", afonCloud());

        List<String> scriptArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--cloud", "-c" -> env.put("AFON_CLOUD", value(args, ++i, arg));
                case "--config-env" -> env.put("AFON_CONFIG_ENV", value(args, ++i, arg));
                case "--cdktf-env" -> env.put("AFON_CDKTF_ENV", value(args, ++i, arg));
                case "--env", "-e" -> env.put("AFON_ENV", value(args, ++i, arg));
                default -> scriptArgs.add(arg);
            }
        }

        if (stackCloud != null) {
            scriptArgs = withStackResolved(AfonCloud.valueOf(stackCloud.toUpperCase()), scriptArgs, new OpsPropertyProvider(), env,
                                           servingComponent);
        }

        try {
            ManagedProcessBuilder pb = new ManagedProcessBuilder(afonCliScriptsSh(env.get("AFON_CLI_DIR")));
            pb.addArgument(scriptPath);
            for (String scriptArg : scriptArgs) {
                pb.addArgument(scriptArg);
            }
            pb.getEnvironment().putAll(env);
            pb.setOutputStreamLogDispatcher(new ProcessLogLevels());
            pb.setDestroyOnShutdown(true)
              .addStdOut(System.out)
              .addStdErr(System.err)
              .setConsoleBufferMaxLines(7000);
            ManagedProcess p = pb.build();
            p.start();
            int exitValue = p.waitForExit();
            if (exitValue != 0) {
                throw new ManagedProcessException(format("%s exited with %d", scriptPath, exitValue));
            }
        } catch (ManagedProcessException e) {
            throw new RuntimeException(format("Failed to run script %s", scriptPath), e);
        }
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(format("Option %s requires a value", flag));
        }
        return args[i];
    }

    private static String afonCliScriptsSh(String afonCliDir) {
        return new File(afonCliDir, "afon-cli-scripts.sh").getAbsolutePath();
    }

    /**
     * Resolves the cli distribution dir this jar was unpacked to, so {@code afon-cli-scripts.sh}
     * can be found regardless of the current working dir.  {@code afon-cli.sh} does not export
     * {@code AFON_CLI_DIR} to the JVM, so it's resolved here from the running jar's own location
     * (both files sit side by side in the packaged distribution) unless overridden.
     * <p>
     * This deliberately reads {@code java.class.path} rather than this class's own
     * {@code CodeSource}: for a Spring Boot fat jar, classes are loaded out of a nested
     * {@code BOOT-INF/classes/} entry whose {@code CodeSource} location is an opaque URL that
     * {@link File#File(java.net.URI)} cannot handle ("URI is not hierarchical").
     * {@code java.class.path} instead reflects exactly what was passed to {@code -jar} at JVM
     * startup, which is unaffected by how the jar's contents get loaded afterward.
     */
    private static String afonCliDir() {
        String override = System.getenv("AFON_CLI_DIR");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
            File jar = new File(classPath).getAbsoluteFile();
            if (jar.isFile()) {
                return jar.getParentFile().getAbsolutePath();
            }
        }
        throw new IllegalStateException(
            "Could not resolve AFON_CLI_DIR: set the AFON_CLI_DIR env var to the cli distribution dir.");
    }

}
