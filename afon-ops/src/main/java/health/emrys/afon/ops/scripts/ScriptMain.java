package health.emrys.afon.ops.scripts;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;

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
 * This is the template the generated {@code *Main} shortcut command classes under
 * {@code health.emrys.afon.ops.scripts.<cloud>} delegate to (see {@code GenerateScriptMains.java}
 * and the {@code generate-sources} binding in afon-ops/pom.xml) - it deliberately has no
 * {@code main} method of its own, so it is not itself picked up as a command.
 */
public class ScriptMain {

    private final String scriptPath;

    public ScriptMain(String scriptPath) {
        this.scriptPath = scriptPath;
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

        try {
            ManagedProcessBuilder pb = new ManagedProcessBuilder(afonCliScriptsSh(env.get("AFON_CLI_DIR")));
            pb.addArgument(scriptPath);
            for (String scriptArg : scriptArgs) {
                pb.addArgument(scriptArg);
            }
            pb.getEnvironment().putAll(env);
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
