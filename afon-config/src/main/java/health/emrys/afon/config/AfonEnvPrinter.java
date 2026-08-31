package health.emrys.afon.config;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.config.AfonProperty.ENV_NAME;
import static health.emrys.afon.config.ConfigUtils.*;
import static java.lang.String.format;

public class AfonEnvPrinter {

    private final AfonPropertyProvider properties;

    public AfonEnvPrinter(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public void printEnv(PrintStream out) {
        String env = format(
                """

                Afon Env:
                
                - name: %s
                - home dir: %s
                - config dir: %s
                - cdktf dir: %s
                - cloud active: %s
                - logging level: %s
                - logging console level: %s
                """,
                properties.getValue(ENV_NAME, "ENV_NAME not set"),
                dirStatus(afonHome()),
                dirStatus(configDir()),
                dirStatus(afonCkdtf()),
                cloudActive(),
                afonLoggingLevel(),
                afonLoggingConsoleLevel());
        out.println(env);
    }

    private String dirStatus(String dir) {
        Path path = Path.of(dir);
        if (Files.isSymbolicLink(path)) {
            try {
                return format("%s (symlink -> %s)", dir, Files.readSymbolicLink(path));
            } catch (IOException e) {
                return format("%s (symlink, unreadable target)", dir);
            }
        } else if (Files.isDirectory(path)) {
            return format("%s (real dir)", dir);
        } else {
            return format("%s (not found)", dir);
        }
    }

    private String cloudActive() {
        return format(( cloudPropertySet() ? cloudPropertyValue() : "no afon.cloud property found"));
    }

}
