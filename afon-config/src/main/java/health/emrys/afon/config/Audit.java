package health.emrys.afon.config;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;

/**
 * To be SARTRE compliant, we need to record the configuration of what we deployed.
 * Central class so it's orthogonal whether we write it to Iceberg or a log.
 */
public class Audit {

    public static final String CONFIGURED_USERS_KEY = "Configured users";

    private final AfonPropertyProvider properties;

    public Audit(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public List<String> configuration() {
        List<String> lines   = new ArrayList<>();
        Date         now     = new Date();
        String       prepend = String.format("%s %s", now, properties.getValue(CLUSTER_NAME));
        Properties   secrets = properties.getSecretProperties();
        properties
            .getProperties()
            .entrySet()
            .stream()
            .filter(x -> !secrets.containsKey(x.getKey()))
            .forEach(x -> {
                lines.add(String.format("%s %s=%s", prepend, x.getKey(), x.getValue()));
            });
        String users = new FileUserProvider()
            .getUsers()
            .stream()
            .map(x -> x.email())
            .collect(Collectors.joining(", "));
        lines.add(String.format("%s %s: %s", prepend, CONFIGURED_USERS_KEY, users));
        return lines;
    }

}
