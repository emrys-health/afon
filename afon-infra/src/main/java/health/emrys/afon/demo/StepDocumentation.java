package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;

import static java.lang.String.format;

public class StepDocumentation extends StepActions {

    private final PrintWriter out;

    public static @NonNull String mvnArgumentsToRun(Class clazz, String[] args) {
        String argLine = "";
        if (args != null && args.length > 0) {
            argLine = format("-Dexec.args=\"%s\"", String.join(" ", args));
        }
        String fullArgs = format("compile -Dexec.mainClass=%s %s exec:java", clazz.getName(), argLine);
        return fullArgs;
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
    public void run(String command, Path path, String... commands) throws ManagedProcessException {
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
        return new Step("waitFor" + domain, "Wait for DNS servers", () -> {
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
    public void deleteTerraformIn(String directory) throws IOException {
        out.println(format("""
                           Delete `%s/%s*`
                           """, directory, TERRAFORM_STATE_FILE_PATTERN));
        out.flush();
    }

    @Override
    public void executeMainOf(Class clazz, String[] args) throws Exception {
        String fullArgs = mvnArgumentsToRun(clazz, args);
        run("mvn",
            null,
            fullArgs);
    }
}
