package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

public class StepDocumentation extends StepActions {

    private final PrintWriter out;

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
                               """, command, Arrays.stream(commands).collect(joining())));
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
                           Delete the directory `%s`
                           """, directory));
        out.flush();
    }
}
