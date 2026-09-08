package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_IMAGE;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_PORT;

/**
 * Abstract based class for KeyCloak deployment configurations.
 *
 * Requires properties:
 *
 * OIDC_DOMAIN_NAME
 * OIDC_PROTOCOL
 * KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME
 * KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD
 * KEYCLOAK_DB
 * KEYCLOAK_DB_URL
 * KEYCLOAK_DB_USERNAME
 * KEYCLOAK_DB_PASSWORD
 */
public abstract class KeyCloakConfig<T extends CloudProvider> extends Construct {
    private static final String APP_NAME = "keycloak";
    private static final String SECRET_NAME = "kcsecrets";
    /**
     * KeyCloak's own env vars (Quarkus config), which the container requires verbatim,
     * we need to map them to {@link AfonProperty} names
     */
    private static final String ENV_KC_HOSTNAME = "KC_HOSTNAME";
    private static final String ENV_KC_DB = "KC_DB";
    private static final String ENV_KC_DB_URL = "KC_DB_URL";
    private static final String ENV_KC_DB_USERNAME = "KC_DB_USERNAME";
    private static final String ENV_KC_DB_PASSWORD = "KC_DB_PASSWORD";
    private static final String ENV_KC_BOOTSTRAP_ADMIN_USERNAME = "KC_BOOTSTRAP_ADMIN_USERNAME";
    private static final String ENV_KC_BOOTSTRAP_ADMIN_PASSWORD = "KC_BOOTSTRAP_ADMIN_PASSWORD";
    private static final ServiceSpecPort SERVICE_SPEC_PORT = ServiceSpecPort.builder()
            .name("web")
            .port(KEYCLOAK_PORT)
            .targetPort("" + KEYCLOAK_PORT)
            .protocol("TCP")
            .build();
    protected final T cloudProvider;

    public KeyCloakConfig(
        @NonNull Construct scope,
        @NonNull AfonPropertyProvider properties,
        @NonNull T cloudProvider) {
        super(scope, APP_NAME + "_scope");
        this.cloudProvider = cloudProvider;

        Map<AfonProperty, String> kcProperties = new LinkedHashMap<>();
        kcProperties.put(KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME, ENV_KC_BOOTSTRAP_ADMIN_USERNAME);
        kcProperties.put(KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD, ENV_KC_BOOTSTRAP_ADMIN_PASSWORD);
        kcProperties.put(KEYCLOAK_DB_URL, ENV_KC_DB_URL);
        kcProperties.put(KEYCLOAK_DB_USERNAME, ENV_KC_DB_USERNAME);
        kcProperties.put(KEYCLOAK_DB_PASSWORD, ENV_KC_DB_PASSWORD);

        Map<String, String> secretsMap = kcProperties.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getValue,
            e -> properties.getValue(e.getKey())
        ));

        Secret kcSecrets = Secret.Builder
            .create(scope, SECRET_NAME)
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(secretsMap)
            .build();

        // KeyCloak 26 takes the scheme of its frontend/issuer URLs from KC_HOSTNAME when that is a full
        // URL. In production KeyCloak sits behind a TLS-terminating load balancer, so the pod itself only
        // speaks http (KC_HTTP_ENABLED=true) but must still advertise https issuers - otherwise
        // oauth2-proxy, which requires https, rejects the OIDC discovery document. We therefore pin the
        // public scheme here. Configurable (defaulting to https) so the acceptance tests can run KeyCloak
        // over plain http.
        String protocol = properties.getValue(OIDC_PROTOCOL, "https");
        String hostname = "https".equalsIgnoreCase(protocol)
                          ? protocol + "://" + properties.getValue(OIDC_DOMAIN_NAME)
                          : properties.getValue(OIDC_DOMAIN_NAME);

        List<DeploymentSpecTemplateSpecContainerEnv> envs = new ArrayList<>();
        envs.add(envVar(ENV_KC_DB, properties.getValue(KEYCLOAK_DB)));
        // KC 26 proxy v2: trust the AWS load balancer's X-Forwarded-* headers. This replaces the
        // deprecated KC_PROXY=edge (which KC 26 warns about). Without it Keycloak treats the
        // TLS-terminated request as plain http and omits the Secure flag from its AUTH_SESSION_ID /
        // KC_RESTART cookies. KC_HOSTNAME_STRICT_BACKCHANNEL was a hostname v1 option and is dropped.
        envs.add(envVar("KC_PROXY_HEADERS", "xforwarded"));
        envs.add(envVar("KC_HOSTNAME_STRICT", "true"));
        envs.add(envVar("KC_HTTP_ENABLED", "true"));
        envs.add(envVar(ENV_KC_HOSTNAME, hostname));
        kcProperties.values().forEach(envName -> envs.add(
            envFromSecret(envName, SECRET_NAME, envName))
        );

        List<String> args = List.of("start");
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(KEYCLOAK_IMAGE)
            .args(args)
            .env(envs)
            .build();
        List<ITerraformDependable> dependants = new ArrayList(cloudProvider.getDependables());
        dependants.add(kcSecrets);
        Map<String, String> labels = Map.of("app", APP_NAME);
        Deployment app = CdkUtils.deploy(
            this,
            dependants,
            labels,
            List.of(keyCloak),
            List.of(),
            APP_NAME);

        dependants.add(app);
        // The Service must not be created until the load balancer controller's mutating webhook is
        // serving (on AWS its failurePolicy=Fail rejects the Service otherwise); getServiceDependables
        // is empty on clouds without such a webhook.
        dependants.addAll(cloudProvider.getServiceDependables());
        // NodePort (not ClusterIP): the AWS Load Balancer Controller uses instance targets by
        // default and requires a node port to build the ingress TargetGroup.
        nodePortService(dependants, labels, KEYCLOAK_SERVICE_NAME, List.of(SERVICE_SPEC_PORT), this);
    }

    protected abstract void configureHttpsAndDNS();
}
