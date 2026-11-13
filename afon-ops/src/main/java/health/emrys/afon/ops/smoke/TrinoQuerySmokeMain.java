package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.configs.TrinoWorkloads.HTTPS_PORT;
import static health.emrys.afon.cdk.configs.TrinoWorkloads.TLS_CA_KEY;
import static health.emrys.afon.cdk.configs.TrinoWorkloads.TLS_SECRET_NAME;
import static health.emrys.afon.cdk.configs.TrinoWorkloads.TRINO_NAMESPACE;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;
import static health.emrys.afon.ops.smoke.LokiClient.withRetries;
import static health.emrys.afon.trino.TrinoServerConfiguration.TOKEN_EXTRA_CREDENTIAL;
import static java.lang.String.format;

public class TrinoQuerySmokeMain {
    private static final Logger       logger       = org.slf4j.LoggerFactory.getLogger(TrinoQuerySmokeMain.class);
    private static final ObjectMapper MAPPER       = new ObjectMapper();
    private static final String       QUERY        = "SELECT COUNT(*) FROM " + POLARIS_TABLE_NAME;
    /**
     * The logger of the OpenLineage event listener that logs each query, see {@code TrinoWorkloads.queryLogProperties}
     */
    private static final String       QUERY_LOGGER = "io.openlineage.client.transports.ConsoleTransport";
    public static final  String       ACCESS_TOKEN = "accessToken";

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

    protected String getTokenFor(AfonUser user) throws IOException {
        // The user's confidential client, of the same name and secret, as it takes no username/password
        String token = new KeyCloakProvider(properties.getOidcDomainName(cloud))
            .clientToken(user.username(), user.password());
        return token;
    }

    public String query(String url, String query, AfonUser user, String token) throws SQLException, IOException {
        return query(url, query, trinoAccessPropertiesFor(user.username(), token));
    }

    public String query(String url, String query, Properties properties) throws SQLException {
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

    /**
     * The token both authenticates the user to Trino, as its access token, and is what Trino calls Polaris with, as the
     * extra credential, since Trino does not pass its access token on.
     */
    public Properties trinoAccessPropertiesFor(String username, String token) {
        Properties cnxnProperties = new Properties();
        cnxnProperties.setProperty("user", username);
        cnxnProperties.setProperty(ACCESS_TOKEN, token);
        cnxnProperties.setProperty("extraCredentials", TOKEN_EXTRA_CREDENTIAL + ":" + token);
        return cnxnProperties;
    }

    /**
     * The users that can get a token, ie those that have a Keycloak confidential client: only the {@code USER_ID} user has one,
     * the others are users with no client of their own (see {@code ConfigureUsersMain#configureOidcUsers}).
     */
    private List<AfonUser> clientUsers() {
        String         userId = clientUserName();
        List<AfonUser> users  = userProvider.getUsers().stream().filter(u -> u.username().equals(userId)).toList();
        if (users.isEmpty()) {
            throw new RuntimeException("Cannot run Trino tests. USER_ID " + userId + " is not a user in users.csv.");
        }
        return users;
    }

    protected String clientUserName() {
        return properties.getValue(AfonProperty.USER_ID);
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
        logger.info("Test passed");
    }

    /**
     * Might take some time for the Polaris logs to appear in Loki
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
                    logger.warn("Polaris logs not (yet) in Loki. Sleeping {} ms then trying again...", ms);
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
            for (AfonUser user : clientUsers()) {
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

    protected List<String> checkCallingTrinoForAllUsers() throws Exception {
        List<String> errors = new ArrayList<>();
        try (KubeClient client = new KubeClient(properties)) {
            OffsetDateTime start    = OffsetDateTime.now();
            V1Pod          trinoPod = client.getOnlyPodBegginingWith("trino-coordinator");
            errors.addAll(hitTrinoWithAllUsers(client, trinoPod));

            V1Pod polarisPod = client.getOnlyPodBegginingWith("polaris-deployment");
            try (LokiClient loki = new LokiClient(client, properties)) {
                errors.addAll(findUsersInPolarisLogsWithBackoff(start, loki, KubeClient.nameOfPod(polarisPod)));
                errors.addAll(findQueriesInTrinoLogsWithBackoff(start, loki, nameOfPod(trinoPod)));
            }
        }
        return errors;
    }

    /**
     * Might take some time for the Trino logs to appear in Loki
     */
    private List<String> findQueriesInTrinoLogsWithBackoff(OffsetDateTime start,
                                                           LokiClient loki,
                                                           String trinoPodName
    ) throws Exception {
        try {
            return withRetries(
                3, () -> {
                    List<String> errors = findQueriesInTrinoLogs(start, loki, trinoPodName);
                    if (!errors.isEmpty()) {
                        throw new IllegalStateException(String.join("\n", errors));
                    }
                    return errors;
                });
        } catch (IllegalStateException e) {
            return List.of(e.getMessage());
        }
    }

    /**
     * @return an error for each user that has no line in Trino's logs that has both the SQL it ran and it as the
     * principal that ran it
     */
    private List<String> findQueriesInTrinoLogs(OffsetDateTime start,
                                                LokiClient loki,
                                                String trinoPodName
    ) throws IOException {
        List<String> errors = new ArrayList<>();
        String       query  = format("{namespace=\"%s\", pod=\"%s\"} |= \"%s\"", TRINO_NAMESPACE, trinoPodName, QUERY_LOGGER);
        List<String> logs   = loki.queryRange(query, start, 1_000);
        if (logs.isEmpty()) {
            errors.add(format("Query '%s' with start time %s returned no logs from Loki", query, start));
            return errors;
        }
        List<String> principals = new ArrayList<>();
        for (String log : logs) {
            String principal = principalThatRan(log, QUERY);
            if (principal != null) {
                principals.add(principal);
            }
        }
        for (AfonUser user : clientUsers()) {
            if (!principals.contains(user.username())) {
                errors.add(format("No Trino log of principal %s running '%s'", user.username(), QUERY));
            }
        }
        return errors;
    }

    /**
     * @param log a line logged by {@value #QUERY_LOGGER}: its OpenLineage event, as JSON, follows the logger's name
     * @return the principal of the event, if it is of the SQL, else null
     */
    static String principalThatRan(String log, String sql) throws IOException {
        int logger = log.indexOf(QUERY_LOGGER);
        int json   = logger < 0 ? -1 : log.indexOf('{', logger);
        if (json < 0) {
            return null;
        }
        JsonNode event = MAPPER.readTree(log.substring(json));
        if (!sql.equals(event.path("job").path("facets").path("sql").path("query").asText())) {
            return null;
        }
        JsonNode principal = event.path("run").path("facets").path("trino_query_context").path("principal");
        return principal.isTextual() ? principal.asText() : null;
    }

    private List<String> hitTrinoWithAllUsers(KubeClient client, V1Pod pod) throws ApiException, SQLException, IOException {
        List<String> errors = new ArrayList<>();
        Path trustStore = Files.createTempFile("trino-ca", ".pem");
        try (PortForwarder forward = new PortForwarder(
            client,
            TRINO_NAMESPACE,
            nameOfPod(pod),
            0,
            HTTPS_PORT)) {
            Files.write(trustStore, client.secretValue(TRINO_NAMESPACE, TLS_SECRET_NAME, TLS_CA_KEY));
            errors.addAll(tryQueriesForAllUsers(httpsUrl(forward.localPort(), trustStore), QUERY));
        } finally {
            Files.deleteIfExists(trustStore);
        }
        return errors;
    }

    /**
     * Over HTTPS, as Trino's clients send no token over plain HTTP, verifying the coordinator's self-signed certificate,
     * which names {@code localhost}, the host of the port-forward.
     *
     * @param trustStore the certificate, as PEM
     */
    String httpsUrl(int localPort, Path trustStore) {
        return format(
            "jdbc:trino://localhost:%d/%s/%s?SSL=true&SSLVerification=FULL&SSLTrustStorePath=%s",
            localPort,
            cloud.toString(),
            POLARIS_NAMESPACE_EXTERNAL,
            trustStore.toAbsolutePath());
    }

    private List<String> tryQueriesForAllUsers(String url, String query) throws SQLException, IOException {
        List<String>   errors              = new ArrayList<>();
        List<AfonUser> users               = clientUsers();
        boolean        isBadPasswordTested = false;
        for (AfonUser user : users) {
            logger.info("Checking Trino access for {}", user.username());
            String token  = getTokenFor(user);
            String result = query(url, query, user, token);
            logger.info("Trino returned {} for {}", result, user.username());
            // Each after a query of the user succeeded, so while the Iceberg client caches the user's Polaris session
            if (!isBadPasswordTested) {
                errors.addAll(tryQueryRefused(
                    url, query, user.username(), "a bad token",
                    trinoAccessPropertiesFor(user.username(), "bad-token")));
                errors.addAll(tryQueryRefused(url, query, user.username(), "no token", noTokenProperties(user.username())));
                String other = IMPERSONATED_PREFIX + user.username();
                errors.addAll(tryQueryRefused(
                    url, query, other, "the token of " + user.username(),
                    trinoAccessPropertiesFor(other, token)));
                isBadPasswordTested = true;
            }
        }
        return errors;
    }

    /**
     * The user that a query tries to run as with another's token, which Trino must refuse, as no user may impersonate
     * another.
     */
    static final String IMPERSONATED_PREFIX = "not-";

    private static Properties noTokenProperties(String username) {
        Properties cnxnProperties = new Properties();
        cnxnProperties.setProperty("user", username);
        return cnxnProperties;
    }

    /**
     * @return an error if Trino ran the query as the user with the credentials, which it must not
     */
    private List<String> tryQueryRefused(String url, String query, String username, String credentials, Properties properties) {
        try {
            query(url, query, properties);
            return List.of(format("Could execute SQL %s as user %s with %s", query, username, credentials));
        } catch (SQLException ex) {
            logger.info("Tried SQL as {} with {} and was correctly refused: {}", username, credentials, ex.getMessage());
            return List.of();
        }
    }

}