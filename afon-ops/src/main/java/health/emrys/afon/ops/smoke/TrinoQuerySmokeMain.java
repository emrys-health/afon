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

/**
 * To run against Kubernetes, run this first:
 * <pre>kubectl port-forward svc/trino 8080:8080</pre>
 * Populate the cloud Polaris with something like:
 * <pre>
 * ./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "GCP acceptancetests smoketesttable260601231212 gs://emrys_afon_core/acceptancetests/smoketesttable260601231212/metadata/00000-0cf4c26f-668b-474c-9542-618d3fba2e19.metadata.json  emryspolaris.click"
 * </pre>
 * or <pre>
 * ./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "AZURE acceptancetests smoketesttable260601231212 abfss://afonstorage@afonstorageaccount.dfs.core.windows.net/acceptancetests/smoketesttable260601231212/metadata/00001-ba11f3b6-6920-4007-9368-ab913506716f.metadata.json  emryspolaris.click"
 * </pre>
 * The query runs as the configured user, with a token from the KeyCloak of the {@code TRE_CLOUD}, which Trino passes on
 * to Polaris: Trino has no credentials of its own for queries.
 */
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

    /**
     * @param args For the table that is not the default one, pass an argument like:
     *             gcp.acceptancetests.smoketesttable260601231212
     *             or
     *             azure.acceptancetests.smoketesttable260601231212
     *             after running the mvn commands above
     */
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

    private List<String> checkPolarisLogsUsers(OffsetDateTime start,
                                               LokiClient loki,
                                               String polarisPodName) throws IOException {
        List<String> errors = new ArrayList<>();
        String       query  = format("{namespace=\"default\", pod=\"%s\"}", polarisPodName);
        List<String> logs   = loki.queryRange(query, start, 1_000);
        if (logs.isEmpty()) {
            errors.add(format("Query '%s' with start time %s returned no logs from Loki",
                              query,
                              start));
        } else {
            logger.info("Polaris logs:\n{}", String.join("\n", logs));
            for (AfonUser user: userProvider.getUsers()) {
                boolean found = false;
                for (String log: logs) {
                    if (log.contains("Resolving principal for credentials: PolarisCredential{principalName="
                                         + user.username())) {
                        found = true;
                        break;
                    }
                }
                if (!found) errors.add("No Polaris log for user " + user.username());
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
                errors.addAll(checkPolarisLogsUsers(start, loki, KubeClient.nameOfPod(polarisPod)));
            }
        }
        return errors;
    }

    private List<String> hitTrinoWithAllUsers(KubeClient client) throws ApiException, SQLException, IOException {
        List<String> errors = new ArrayList<>();
        V1Pod        pod    = client.getOnlyPodBegginingWith("trino-coordinator");
        try (PortForwarder forward = new PortForwarder(client,
                                                       TRINO_NAMESPACE,
                                                       nameOfPod(pod),
                                                       0,
                                                       8080)) {
            int localPort = forward.localPort();
            String url = format("jdbc:trino://localhost:%d/%s/%s",
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
            logger.info("Tried a deliberately bad token for {} and was correctly refused",
                        user.username());
        }
        return errors;
    }

}