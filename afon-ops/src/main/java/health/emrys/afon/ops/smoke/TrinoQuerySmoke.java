package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.cdk.configs.TrinoWorkloads;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import io.kubernetes.client.openapi.models.V1Pod;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.sql.*;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkHelper.TRINO_SERVICE_NAME;
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
public class TrinoQuerySmoke {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TrinoQuerySmoke.class);

    private final SmokeConfig config;

    public TrinoQuerySmoke() {
        this.config = new SmokeConfig();
    }


    public String query(String url, AfonUser user) throws SQLException, IOException {
        String     query      = "SELECT COUNT(*) FROM " + POLARIS_TABLE_NAME;
        Properties properties = trinoAccessPropertiesFor(user);
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

    private @NonNull Properties trinoAccessPropertiesFor(AfonUser user) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("user", user.username());
        String token = new KeyCloakProvider(config.getProperties().getOidcDomainName(config.cloud()))
            .userToken(user.username(), user.password());
        properties.setProperty("extraCredentials", TOKEN_EXTRA_CREDENTIAL + ":" + token);
        return properties;
    }

    /**
     * @param args For the table that is not the default one, pass an argument like:
     *             gcp.acceptancetests.smoketesttable260601231212
     *             or
     *             azure.acceptancetests.smoketesttable260601231212
     *             after running the mvn commands above
     */
    public static void main(String[] args) {
        try {
            TrinoQuerySmoke app = new TrinoQuerySmoke();
            app.checkCallingTrinoForAllUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkCallingTrinoForAllUsers() throws Exception {
        try (KubeClient client = new KubeClient(config.getProperties())) {
            V1Pod pod = client.getOnlyPodBegginingWith("trino-coordinator");
            try (PortForwarder forward = new PortForwarder(client,
                                                           TRINO_NAMESPACE,
                                                           nameOfPod(pod),
                                                           0,
                                                           8080)) {
                int localPort = forward.localPort();
                String url = format("jdbc:trino://localhost:%d/%s/%s",
                                    localPort,
                                    config.cloud().toString(),
                                    POLARIS_NAMESPACE_EXTERNAL);
                for (AfonUser user : new FileUserProvider().getUsers()) {
                    logger.info("Checking Trino access for {}", user.username());
                    String result = query(url, user);
                    System.out.println(result);
                }
            }
        }
    }

}