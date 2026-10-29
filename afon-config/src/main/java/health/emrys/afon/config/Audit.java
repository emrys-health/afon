package health.emrys.afon.config;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * To be SARTRE compliant, we need to record the configuration of what we deployed.
 * Central class so it's orthogonal whether we write it to Iceberg or a log.
 */
public class Audit {

    private static final Logger logger               = getLogger(Audit.class);
    public static final  String CONFIGURED_USERS_KEY = "Configured users";

    private final AfonPropertyProvider properties;
    private final String               prepend;

    public Audit(AfonPropertyProvider properties) {
        this.properties = properties;
        Date now = new Date();
        prepend = format("%s %s", now, properties.getValue(CLUSTER_NAME));
    }

    public List<String> configuration() {
        List<String> lines = new ArrayList<>();
        appendNonSecrets(lines);
        lines.add(allUsers());
        lines.add(whitelistYamlIfPresent());
        logger.info("Audited configuration:\n{}", String.join("\n", lines));
        return lines;
    }

    private void appendNonSecrets(List<String> lines) {
        Properties secrets = properties.getSecretProperties();
        properties
            .getProperties()
            .entrySet()
            .stream()
            .filter(x -> !secrets.containsKey(x.getKey()))
            .forEach(x -> {
                lines.add(format("%s %s=%s", prepend, x.getKey(), x.getValue()));
            });
    }

    private String allUsers() {
        String users = new FileUserProvider()
            .getUsers()
            .stream()
            .map(x -> x.email())
            .collect(Collectors.joining(", "));
        String allUsers = format("%s %s: %s", prepend, CONFIGURED_USERS_KEY, users);
        return allUsers;
    }

    private String whitelistYamlIfPresent() {
        String path      = ConfigUtils.configDir() + File.separator + ConfigUtils.WHITELIST_FILE;
        Path   whitelist = Path.of(path);
        String content   = "";
        if (Files.isRegularFile(whitelist)) {
            try {
                content = Files.readString(whitelist, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read whitelist " + whitelist, e);
            }
        }
        return format(
            """
            %s Configured allowlist as defined in %s:
            %s
            """, prepend, path, content);
    }

}
