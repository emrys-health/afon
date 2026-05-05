package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.config.AfonProperties;
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

    public static @NonNull String mvnArgumentsToRun(Class clazz,
                                                    String[] args,
                                                    String command) {
        return mvnArgumentsToRun(clazz, args, new Properties(), command);
    }


    public static @NonNull String mvnArgumentsToRun(Class clazz,
                                                    String[] args,
                                                    Properties overrides,
                                                    String command) {
        String argLine = "";
        if (args != null && args.length > 0) {
            argLine = format("-Dexec.args=\"%s\"", String.join(" ", args));
        }
        String overridesArgLine = "";
        if (overrides != null && overrides.size() > 0) {
            overridesArgLine = overrides.entrySet().stream().map(x ->
                format("-D%s=%s ", x.getKey(), x.getValue())
                ).collect(Collectors.joining());
        }
        return format("%s compile -Dexec.mainClass=%s %s exec:%s",
                      overridesArgLine, clazz.getName(), argLine, command);
    }

    public StepDocumentation(OutputStream out) {
        this.out = new PrintWriter(out);
    }

    @Override
    public void delete(Path x) {
        out.println(format("Delete `%s`", x));
        out.flush();
    }

    @Override
    public void run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
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
    }

    @Override
    public Step waitFor(String domain) {
        return new Step(stepNameWaitingFor(domain), "Wait for DNS servers", () -> {
            out.println(format("""
                                   Wait for DNS servers to recognise %s. On a Unix system, running:
                                   ```shell
                                   dig A %s +short
                                   ```
                                   will show you the IP address when the DNS servers have updated.
                                   """, domain, domain));
            out.flush();
        });
    }

    @Override
    public void deleteTerraformStateFiles(AfonProperties.SupportedCloudsEnum id) throws IOException {
        out.println(format("""
                           Delete `./%s%s*`
                           """, TERRAFORM_STATE_FILE_PATTERN, id));
        out.flush();
    }

    @Override
    public void readFromSnowflake(String table, Properties properties) throws Exception {
        out.println(format("""
                           Read from table `%s` in Snowflake using JDBC URL `%s`
                           """, table, AfonProperties.SNOWFLAKE_JDBC_URL.getValueFrom(properties)));
        out.flush();
    }

    @Override
    public void executeMainOf(Class clazz, String[] args, Properties overrides) throws Exception {
        String fullArgs = mvnArgumentsToRun(clazz, args, overrides, "java");
        run("mvn",
            null,
            fullArgs);
    }
}
