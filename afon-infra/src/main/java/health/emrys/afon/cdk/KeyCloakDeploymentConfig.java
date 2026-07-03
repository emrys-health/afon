package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperty;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.TFUtils.*;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_IMAGE;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_PORT;

/**
 * These environment variable, although inappropriate for Docker, get the app to start:
 * docker run  -it -p 127.0.0.1:8080:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
 * -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
 * -e KC_DB_URL=jdbc:postgresql://polaris-db.crsocia0o0bd.eu-west-2.rds.amazonaws.com:5432/keycloak \
 * -e KC_DB_USERNAME=postgres \
 * -e KC_DB_PASSWORD=3mry5af0n \
 * -e KC_DB=postgres \
 * -e KC_PROXY=edge \
 * -e KC_HOSTNAME=localhost \
 * -e KC_HOSTNAME_STRICT=true \
 * -e KC_HOSTNAME_STRICT_BACKCHANNEL=false \
 * -e KC_HTTP_ENABLED=true \
 * quay.io/keycloak/keycloak:26.6.3 start
 */
public abstract class KeyCloakDeploymentConfig<T extends CloudProvider> extends Construct {
    public static final String          KEYCLOAK_INGRESS       = "keycloak-ingress";
    public static final String          KEYCLOAK_HTTPS_INGRESS = "keycloak-https-ingress";
    public static final String          APP_NAME               = "keycloak";
    public static final String          KC_SVC                 = "keycloak-service";
    public static final ServiceSpecPort KC_PORT                = ServiceSpecPort.builder()
                                                                                .name("web")
                                                                                .port(KEYCLOAK_PORT)
                                                                                .targetPort("" + KEYCLOAK_PORT)
                                                                                .protocol("TCP")
                                                                                .build();
    private final       String          SECRET_NAME            = "kcsecrets";
    protected final     T               cloudProvider;

    public KeyCloakDeploymentConfig(
        @NotNull Construct scope,
        @NotNull Properties properties,
        @NotNull T cloudProvider) {
        super(scope, APP_NAME + "_scope");
        this.cloudProvider = cloudProvider;

        // List, not Set: Set.of() iteration order is randomised per JVM, which made
        // every synth reorder the env vars and produce a spurious Terraform diff.
        List<AfonProperty> kcProperties = List.of(
            KC_BOOTSTRAP_ADMIN_USERNAME,
            KC_BOOTSTRAP_ADMIN_PASSWORD,
            KC_DB_URL,
            KC_DB_USERNAME,
            KC_DB_PASSWORD);

        Map<String, String> secretsMap = kcProperties.stream().collect(Collectors.toMap(
            AfonProperty::getPropertyName,
            x -> x.getValueFrom(properties)
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
        String protocol = KC_PROTOCOL.getValueFrom(properties, "https");
        String hostname = "https".equalsIgnoreCase(protocol)
                          ? protocol + "://" + KC_HOSTNAME.getValueFrom(properties)
                          : KC_HOSTNAME.getValueFrom(properties);

        List<DeploymentSpecTemplateSpecContainerEnv> envs = new ArrayList<>();
        envs.add(envVar(KC_DB.getPropertyName(), KC_DB.getValueFrom(properties)));
        // KC 26 proxy v2: trust the AWS load balancer's X-Forwarded-* headers. This replaces the
        // deprecated KC_PROXY=edge (which KC 26 warns about). Without it Keycloak treats the
        // TLS-terminated request as plain http and omits the Secure flag from its AUTH_SESSION_ID /
        // KC_RESTART cookies. KC_HOSTNAME_STRICT_BACKCHANNEL was a hostname v1 option and is dropped.
        envs.add(envVar("KC_PROXY_HEADERS", "xforwarded"));
        envs.add(envVar("KC_HOSTNAME_STRICT", "true"));
        envs.add(envVar("KC_HTTP_ENABLED", "true"));
        envs.add(envVar(KC_HOSTNAME.getPropertyName(), hostname));
        kcProperties.stream().forEach(x -> envs.add(
            envFromSecret(x.getPropertyName(), SECRET_NAME, x.getPropertyName()))
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
        Deployment app = DeploymentUtils.deploy(
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
        nodePortService(dependants, labels, KC_SVC, List.of(KC_PORT), this);
    }

    protected abstract void configureHttpsAndDNS();
}
