package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.config.AfonProperties.AfonCloud;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class StepDocumentation extends StepActions {

    private final PrintWriter out;

    public static @NonNull String afonArgumentsToRun(Class clazz,
                                                     String[] args,
                                                     String command) {
        return afonArgumentsToRun(clazz, args, new Properties(), command);
    }


    public static @NonNull String afonArgumentsToRun(Class clazz,
                                                     String[] args,
                                                     Properties overrides,
                                                     String command) {
        String argLine = "";
        if (args != null && args.length > 0) {
            argLine = format("%s", String.join(" ", args));
            if (!argLine.isEmpty()){
                argLine=format("--exec-args \"%s\"", argLine);
            }
        }
        String overridesArgLine = "";
        if (overrides != null && overrides.size() > 0) {
            overridesArgLine = overrides.entrySet().stream().map(x ->
                format("%s=%s ", x.getKey(), x.getValue())
                ).collect(Collectors.joining());
            if (!overridesArgLine.isEmpty()){
                overridesArgLine=format("--exec-envs \"%s\"", overridesArgLine);
            }
        }
        return format("-a %s --exec-class %s %s %s",
                      command, clazz.getName(), argLine, overridesArgLine);
    }

    public StepDocumentation(OutputStream out) {
        this.out = new PrintWriter(out);
    }

    @Override
    public void executeMainOf(Class mainClass, String[] args, Properties overrides) throws Exception {
        String fullArgs = afonArgumentsToRun(mainClass, args, overrides, "exec");
        run("./afon.sh",
                null,
                fullArgs);
    }

    @Override
    public int run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
        if (path != null) {
            out.println(format("In `%s` ", path));
        } else {
            out.println("In the top level directory ");
        }
        out.println(format("""
                               run
                               ```shell
                               %s %s
                               ```
                               """, command, String.join(" ", commands)));
        out.flush();
        return 0;
    }

    @Override
    public Step waitOnDNS(String domain) {
        return new Step(stepNameWaitOn(domain), "Wait on DNS", () -> {
            out.println(format("""
                                   Wait on DNS for domain %s. On a Unix system, running:
                                   ```shell
                                   dig A %s +short
                                   ```
                                   will show you the IP address when the DNS servers have updated.
                                   """, domain, domain));
            out.flush();
        });
    }

    @Override
    public void deleteTerraformStateFiles(AfonCloud id) throws IOException {
        out.println(format("""
                           Delete `./%s%s*`
                           """, TERRAFORM_STATE_FILE_PATTERN, id));
        out.flush();
    }

    @Override
    public void deletePath(Path path) {
        out.println(format("Delete `%s`", path));
        out.flush();
    }

    @Override
    public void runSnowflakeSql(String[] statements) {
        out.println("Run Snowflake SQL:");
        for (String sql : statements) {
            out.println(format(
                    """
                    ```sql
                    %s
                    ```
                    """, sql));
            out.flush();
        }
    }


}
