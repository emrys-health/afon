package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.DockerImages;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.trino.KeyCloakTrinoServerConfiguration;
import health.emrys.afon.trino.TrinoServerConfiguration;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.internalRealmUrl;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.tokenEndpoint;
import static health.emrys.afon.trino.TrinoServerConfiguration.CREDENTIAL_ENV;
import static health.emrys.afon.trino.TrinoServerConfiguration.SHARED_SECRET_ENV;

/**
 * Cloud-agnostic deployment of Trino via its official Helm chart — the counterpart to
 * {@link SparkWorkloads}. Trino has no first-party operator (unlike the Spark Kubernetes
 * Operator that {@link SparkConfig} installs and {@link SparkWorkloads} drives with CRs), so the
 * chart is the maintained deployment path, and this mirrors how the project already installs
 * cert-manager / nginx / the Spark operator (all Helm {@link Release}s).
 *
 * <p>Catalogs follow {@code SparkWarehouseConfig}: one Iceberg REST catalog per
 * {@link AfonCloud}, all pointing at the same Polaris host with {@code warehouse} set
 * to the cloud's Polaris catalog name. They are passed through the chart's {@code
 * additionalCatalogs} value, so the chart's default {@code tpch}/{@code tpcds} catalogs remain.
 *
 * <p>The catalog files hold no credentials (see {@link TrinoServerConfiguration}): the credential with which each
 * catalog starts is in the {@code Secret} {@value #SECRET_NAME}, which the pods read as {@code CREDENTIAL_ENV}, and
 * the token for each query, which Trino passes on to Polaris, comes from the client.
 *
 * <p>The client authenticates to Trino with the same OIDC token, which the coordinator checks against KeyCloak's keys, and
 * may run queries only as the user that it is of (see {@link #jwtAuthenticationConfig} and {@link #noImpersonation}).
 */
public class TrinoWorkloads {

    /**
     * Chart version pinned for reproducibility; {@link #IMAGE_TAG} overrides its appVersion.
     */
    private static final String CHART_VERSION   = "1.42.2";
    private static final int    WORKERS         = 2;
    private static final int    TRINO_PORT      = 8080;
    private static final String SECRET_NAME     = "trino-polaris";
    public static final  String TRINO_NAMESPACE = "default";

    private final TrinoServerConfiguration trinoConfig;
    private final AfonCatalogs             afonCatalogs;
    private final String                   realmUrl;

    /**
     * @param oidcDomainName the domain of the KeyCloak that issues Polaris' tokens, as Polaris answers HTTP 501 from its
     *                       own token endpoint
     */
    public TrinoWorkloads(AfonPropertyProvider properties,
                          AfonUserProvider users,
                          String polarisDomainName,
                          String oidcDomainName) {
        this.trinoConfig  = new KeyCloakTrinoServerConfiguration(properties, users, polarisDomainName,
                                                                 tokenEndpoint(oidcDomainName, "https"));
        this.afonCatalogs = new AfonCatalogs(properties);
        this.realmUrl     = internalRealmUrl(oidcDomainName, "https");
    }

    /**
     * Installs the Trino chart against the cluster targeted by {@code helmProvider}. The release
     * id/name is kept stable ("trino") so existing stack state is preserved and the resulting
     * {@code Service} is named {@link CdkHelper#TRINO_SERVICE_NAME}.
     *
     * @param scope        the CDKTF stack the release is scoped to.
     * @param helmProvider the Helm provider wired to the target cluster (Azure aliases its
     *                     provider; hence this is explicit rather than relying on a default).
     * @param id           the cloud whose catalog is listed first; every cloud is still
     *                     registered as a catalog, exactly as Spark configures every cloud.
     * @param dependsOn    must include the cluster's dependables (so the API server is reachable).
     */
    public void deploy(@NonNull Construct scope,
                       @NonNull HelmProvider helmProvider,
                       @NonNull AfonCatalog id,
                       @NonNull List<? extends ITerraformDependable> dependsOn) {
        Map<String, Object> values = Map.ofEntries(
            Map.entry("image", Map.of("tag", DockerImages.TRINO_IMAGE_TAG)),
            Map.entry("server", Map.of(
                "workers", WORKERS, // Integer type
                "config", Map.of("authenticationType", "JWT"),
                "coordinatorExtraConfig", jwtAuthenticationConfig(realmUrl))),
            Map.entry("additionalConfigProperties", List.of("internal-communication.shared-secret=${ENV:" + SHARED_SECRET_ENV + "}")),
            Map.entry("accessControl", noImpersonation()),
            Map.entry("initContainers", Map.of("coordinator", List.of(waitForKeys(realmUrl)))),
            Map.entry("coordinator", nodeSettings()),
            Map.entry("worker", nodeSettings()),
            Map.entry("service", Map.of("type", "ClusterIP", "port", TRINO_PORT)),
            Map.entry("env", List.of(secretEnv(CREDENTIAL_ENV), secretEnv(SHARED_SECRET_ENV))),
            Map.entry("eventListenerProperties", queryLogProperties()),
            Map.entry("additionalCatalogs", catalogFiles(id.toString())));

        Secret credential = Secret.Builder
            .create(scope, "trinoPolarisSecret")
            .metadata(SecretMetadata.builder().name(SECRET_NAME).namespace(TRINO_NAMESPACE).build())
            .data(Map.of(CREDENTIAL_ENV, trinoConfig.credential(), SHARED_SECRET_ENV, trinoConfig.sharedSecret()))
            .type("Opaque")
            .dependsOn(dependsOn)
            .build();
        List<ITerraformDependable> releaseDependsOn = new ArrayList<>(dependsOn);
        releaseDependsOn.add(credential);

        // JSON is valid YAML, so a JSON serializer produces a values document Helm
        // accepts — avoiding a YAML dependency while keeping the multi-line catalog strings.
        // Canonical (key-sorted) so the values string, which Helm diffs as text, is stable.  Escaped, as the catalogs'
        // ${ENV:...} are Trino's, not Terraform's.
        String valuesYaml = CdkUtils.escapeTerraformTemplate(CdkUtils.toCanonicalJson(values));

        Release.Builder.create(scope, "trino")
                       .provider(helmProvider)
                       .name("trino")
                       .chart(CdkUtils.vendoredChart(scope, "trinoChart", "trino-" + CHART_VERSION + ".tgz"))
                       .namespace(TRINO_NAMESPACE)
                       .atomic(true)
                       .timeout(600)
                       .values(List.of(valuesYaml))
                       .dependsOn(releaseDependsOn)
                       .build();
    }

    /**
     * What the coordinator and the workers need to start on a cluster with little to spare.  Without requests the pods
     * are best-effort, so the JVM is starved of CPU by the pods that have them, and its startup is slower than the
     * chart's startup probe allows, which restarts it, every time: the release then times out.  The probe's timeout and
     * retries are those of a JVM that starts slowly, not of one that is hung.
     */
    private static Map<String, Object> secretEnv(String name) {
        return Map.of("name", name, "valueFrom", Map.of("secretKeyRef", Map.of("name", SECRET_NAME, "key", name)));
    }

    /**
     * Makes the coordinator authenticate every client by its OIDC token, as KeyCloak's realm signed it, so that a query
     * runs as the user the token is of, its {@code polaris_principal}, which is the Polaris principal that the same token
     * is when Trino passes it on.  Plain HTTP is allowed, as Trino is only reachable in the cluster, and the JDBC driver
     * sends the token over it.
     *
     * @param realmUrl the realm's URL, as the issuer of its tokens names it, eg {@code https://<oidc domain>/realms/polaris}
     */
    static String jwtAuthenticationConfig(String realmUrl) {
        return String.join("\n",
                           "http-server.authentication.jwt.key-file=" + keysUrl(realmUrl),
                           "http-server.authentication.jwt.required-issuer=" + realmUrl,
                           "http-server.authentication.jwt.principal-field=polaris_principal",
                           "http-server.authentication.allow-insecure-over-http=true");
    }

    /** The realm's signing keys (JWKS), with which the coordinator checks each client's token. */
    static String keysUrl(String realmUrl) {
        return realmUrl + "/protocol/openid-connect/certs";
    }

    /**
     * Blocks the coordinator's start until the realm serves its signing keys, as {@code JupyterMfaConfig#waitForIssuer} does
     * oauth2-proxy's.
     *
     * <p>The coordinator reads the keys once, at startup, and fails to start if it cannot: nothing else makes Trino wait for
     * KeyCloak, whose public hostname may not resolve, nor serve, until minutes after Trino is scheduled, eg on a new stack.
     * Without this gate the coordinator crash-loops, and its back-off overruns the release's timeout, which, being atomic,
     * then uninstalls it.  Curl's {@code -f} treats a load balancer's 5xx as a failure too, so this waits for KeyCloak to
     * actually serve.  Unbounded: if the keys never come, the pod stays in {@code Init:0/1} with this container's log naming
     * what it waits for.
     */
    static Map<String, Object> waitForKeys(String realmUrl) {
        String script = String.join("\n",
                                    "until curl -sfo /dev/null \"$KEYS_URL\"; do",
                                    "  echo \"waiting for the OIDC realm's keys at $KEYS_URL\"",
                                    "  sleep 5",
                                    "done",
                                    "echo \"OIDC realm's keys reachable\"");
        return Map.of(
            "name", "wait-for-oidc-keys",
            "image", DockerImages.CURL_IMAGE,
            "command", List.of("sh", "-c", script),
            "env", List.of(Map.of("name", "KEYS_URL", "value", keysUrl(realmUrl))));
    }

    /**
     * File-based access control with no rules: every user may do what they could before, as Polaris decides what they may
     * read, but none may run queries as another user than the one their token authenticated, which Trino's default access
     * control allows, so that no user can use another's Polaris session, which the Iceberg client caches by user.
     */
    static Map<String, Object> noImpersonation() {
        return Map.of("type", "configmap", "configFile", "rules.json", "rules", Map.of("rules.json", "{}"));
    }

    private static Map<String, Object> nodeSettings() {
        return Map.of(
            "resources", Map.of("requests", Map.of("cpu", "500m", "memory", "1Gi")),
            "startupProbe", Map.of("initialDelaySeconds", 10, "periodSeconds", 5, "timeoutSeconds", 10,
                                   "failureThreshold", 60));
    }

    /**
     * Logs every query, with who ran it, to the coordinator's stdout (and so to Loki) - Trino itself logs neither.  The
     * OpenLineage listener ships with Trino and its console transport writes, per query, a START and a COMPLETE event as
     * one line of JSON, with the SQL in {@code job.facets.sql.query} and the user in
     * {@code run.facets.trino_query_context.user}.  By default it ignores SELECTs, so every query type is listed.  The
     * COMPLETE event holds the query's plan and statistics too, which {@code disabled-facets} does not (as of 481)
     * remove.  The URI only names the job's namespace; Trino is never called on it.
     */
    private static List<String> queryLogProperties() {
        return List.of(
            "event-listener.name=openlineage",
            "openlineage-event-listener.transport.type=CONSOLE",
            "openlineage-event-listener.trino.uri=http://" + CdkHelper.TRINO_SERVICE_NAME + ":" + TRINO_PORT,
            "openlineage-event-listener.trino.include-query-types="
            + "SELECT,EXPLAIN,DESCRIBE,ANALYZE,INSERT,UPDATE,DELETE,MERGE,ALTER_TABLE_EXECUTE,DATA_DEFINITION");
    }

    /**
     * One catalog per supported cloud, keyed by the cloud name (the chart writes each entry to
     * {@code <name>.properties}). Mirrors {@code SparkWarehouseConfig.sparkConfigs}.
     */
    private Map<String, String> catalogFiles(String defaultCatalog) {
        // LinkedHashMap so the default catalog is emitted first for readability.
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put(defaultCatalog, trinoConfig.catalogProperties(defaultCatalog));
        // Only the generic types fit the catalog generated here (Trino also requires lowercase,
        // cloud-prefixed names); custom types such as glue were never exposed to Trino.
        for (AfonCatalog catalog : afonCatalogs.allIncluding("internal", "external")) {
            String catalogName = catalog.toString();
            if (catalogName.equals(defaultCatalog)) {
                continue;
            }
            catalogs.put(catalogName, trinoConfig.catalogProperties(catalogName));
        }
        return catalogs;
    }


}
