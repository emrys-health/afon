package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.configs.TrinoWorkloads.TRINO_NAMESPACE;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;
import static health.emrys.afon.trino.TrinoServerConfiguration.TOKEN_EXTRA_CREDENTIAL;
import static java.lang.String.format;

public class TrinoQuerySmokeMain {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TrinoQuerySmokeMain.class);

    private final AfonCloud            cloud;
    private final AfonPropertyProvider properties;
    private final AfonUserProvider     userProvider;

    public TrinoQuerySmokeMain(AfonCloud cloud, AfonPropertyProvider properties) {
        this.cloud        = cloud;
        this.properties   = properties;
        this.userProvider = new FileUserProvider();
    }

    public TrinoQuerySmokeMain() {
        this(new SmokeConfig().cloud(), new SmokeConfig().getProperties());
    }

    public String query(String url, AfonUser user, String query) throws SQLException, IOException {
        String token = getTokenFor(user);
        return query(url, query, user, token);
    }

    private String getTokenFor(AfonUser user) throws IOException {
        String token = new KeyCloakProvider(properties.getOidcDomainName(cloud))
            .userToken(user.username(), user.password());
        return token;
    }

    public String query(String url, String query, AfonUser user, String token) throws SQLException, IOException {
        Properties properties = trinoAccessPropertiesFor(user, token);
        logger.info("Executing {} against {}", query, url);
        try (Connection connection = DriverManager.getConnection(url, properties);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return "empty, like my life";
    }

    private @NonNull Properties trinoAccessPropertiesFor(AfonUser user, String token) throws IOException {
        Properties cnxnProperties = new Properties();
        cnxnProperties.setProperty("user", user.username());
        cnxnProperties.setProperty("extraCredentials", TOKEN_EXTRA_CREDENTIAL + ":" + token);
        return cnxnProperties;
    }

    public static void main(String[] args) throws Exception {
        try {
            TrinoQuerySmokeMain app    = new TrinoQuerySmokeMain();
            List<String>        errors = app.checkCallingTrinoForAllUsers();
            if (!errors.isEmpty()) {
                String msg = String.join("\n", errors);
                logger.error(msg);
                throw new RuntimeException(msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Might take some time for the logs to appear in Polaris
     */
    private List<String> findUsersInPolarisLogsWithBackoff(OffsetDateTime start,
                                                           LokiClient loki,
                                                           String polarisPodName
    ) throws IOException, InterruptedException {
        List<String> errors     = new ArrayList<>();
        int          numRetries = 3;
        for (int i = 1; i <= numRetries; i++) {
            List<String> xs = findUsersInPolarisLogs(start, loki, polarisPodName);
            if (xs.isEmpty()) {
                return List.of();
            } else {
                if (i == numRetries) {
                    errors.addAll(xs);
                } else {
                    int ms = 1_000 * i;
                    logger.warn("Logs not (yet) in Polaris. Sleeping {} ms then trying again...", ms);
                    Thread.sleep(ms);
                }
            }
        }

        return errors;
    }

    private List<String> findUsersInPolarisLogs(OffsetDateTime start,
                                                LokiClient loki,
                                                String polarisPodName
    ) throws IOException {
        List<String> errors = new ArrayList<>();
        String       query  = format("{namespace=\"default\", pod=\"%s\"}", polarisPodName);
        List<String> logs   = loki.queryRange(query, start, 1_000);
        if (logs.isEmpty()) {
            errors.add(format(
                "Query '%s' with start time %s returned no logs from Loki",
                query,
                start));
        } else {
            logger.info("Polaris logs:\n{}", String.join("\n", logs));
            for (AfonUser user : userProvider.getUsers()) {
                boolean found = false;
                for (String log : logs) {
                    if (log.contains("Resolving principal for credentials: PolarisCredential{principalName="
                                     + user.username())) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    errors.add("No Polaris log for user " + user.username());
            }
        }
        return errors;
    }

    private List<String> checkCallingTrinoForAllUsers() throws Exception {
        List<String> errors = new ArrayList<>();
        try (KubeClient client = new KubeClient(properties)) {
            OffsetDateTime start = OffsetDateTime.now();
            errors.addAll(hitTrinoWithAllUsers(client));

            V1Pod polarisPod = client.getOnlyPodBegginingWith("polaris-deployment");
            try (LokiClient loki = new LokiClient(client, properties)) {
                errors.addAll(findUsersInPolarisLogsWithBackoff(start, loki, KubeClient.nameOfPod(polarisPod)));
            }
        }
        return errors;
    }

    private List<String> hitTrinoWithAllUsers(KubeClient client) throws ApiException, SQLException, IOException {
        List<String> errors = new ArrayList<>();
        V1Pod        pod    = client.getOnlyPodBegginingWith("trino-coordinator");
        try (PortForwarder forward = new PortForwarder(
            client,
            TRINO_NAMESPACE,
            nameOfPod(pod),
            0,
            8080)) {
            int localPort = forward.localPort();
            String url = format(
                "jdbc:trino://localhost:%d/%s/%s",
                localPort,
                cloud.toString(),
                POLARIS_NAMESPACE_EXTERNAL);
            String query = "SELECT COUNT(*) FROM " + POLARIS_TABLE_NAME;
            errors.addAll(tryQueriesForAllUsers(url, query));
        }
        return errors;
    }

    private List<String> tryQueriesForAllUsers(String url, String query) throws SQLException, IOException {
        List<String>   errors = new ArrayList<>();
        List<AfonUser> users  = userProvider.getUsers();
        if (users.isEmpty()) {
            throw new RuntimeException("Cannot run Trino tests. No users found.");
        }
        boolean isBadPasswordTested = false;
        for (AfonUser user : users) {
            logger.info("Checking Trino access for {}", user.username());
            String result = query(url, user, query);
            logger.info("Trino returned {} for {}", result, user.username());
            if (!isBadPasswordTested) {
                errors.addAll(tryQueryWithBadToken(user, query));
                isBadPasswordTested = true;
            }
        }
        return errors;
    }

    private List<String> tryQueryWithBadToken(AfonUser user, String query) throws IOException {
        List<String> errors = new ArrayList<>();
        try {
            query(query, query, user, "bad token");
            errors.add(format("Could execute SQL {} for user {} using a bad token", query, user.username()));
        } catch (SQLException ex) {
            logger.info(
                "Tried a deliberately bad token for {} and was correctly refused",
                user.username());
        }
        return errors;
    }

}