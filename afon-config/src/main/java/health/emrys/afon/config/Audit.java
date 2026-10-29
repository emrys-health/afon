package health.emrys.afon.config;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * To be SARTRE compliant, we need to record the configuration of what we deployed.
 * Central class so it's orthogonal whether we write it to Iceberg or a log.
 */
public class Audit {

    private static final Logger logger = getLogger(Audit.class);
    public static final  String CONFIGURED_USERS_KEY = "Configured users";
    public static final String REDACTED = "REDACTED";

    private final AfonPropertyProvider properties;
    private final String               prepend;

    public Audit(AfonPropertyProvider properties) {
        this.properties = properties;
        Date now = new Date();
        prepend = format("%s %s", now, properties.getValue(CLUSTER_NAME));
    }

    public List<String> configuration() {
        List<String> lines = new ArrayList<>();
        lines.add(format("%s Properties taken from %s deployed by %s",
                         prepend,
                         ConfigUtils.configDir(),
                         System.getProperty("user.name")));
        appendNonSecrets(lines);
        lines.add(allUsers());
        lines.addAll(whitelistYamlIfPresent());
        logger.info("Audited configuration:\n{}", String.join("\n", lines));
        return lines;
    }

    private void appendNonSecrets(List<String> lines) {
        Properties secrets = properties.getSecretProperties();
        properties
            .getProperties()
            .entrySet()
            .stream()
            .map(x -> {
                if (secrets.containsKey(x.getKey())) {
                    return new AbstractMap.SimpleEntry(x.getKey(), REDACTED);
                }
                return x;
            })
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

    private List<String> whitelistYamlIfPresent() {
        String path      = ConfigUtils.configDir() + File.separator + ConfigUtils.WHITELIST_FILE;
        Path   whitelist = Path.of(path);
        if (Files.isRegularFile(whitelist)) {
            try {
                String content = Files.readString(whitelist, StandardCharsets.UTF_8);
                return List.of(format(
                    """
                    %s Configured allowlist as defined in %s:
                    %s
                    """, prepend, path, content));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read whitelist " + whitelist, e);
            }
        }
        return List.of();
    }

}
