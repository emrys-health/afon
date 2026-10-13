package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.cdk.HealthCheck;
import health.emrys.afon.cdk.HttpsRoute;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerReadinessProbe;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMapItems;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.*;
import static health.emrys.afon.config.AfonProperty.*;
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
public class KeyCloakConfig extends Construct {
    private static final String APP_NAME = "keycloak";
    private static final String SECRET_NAME = "kcsecrets";
    private static final String THEME_CONFIGMAP = "keycloak-theme";
    /** KeyCloak serves its health checks on its management interface, not on {@code KEYCLOAK_PORT}. */
    public static final int KEYCLOAK_MANAGEMENT_PORT = 9000;
    public static final String MANAGEMENT_PORT_NAME = "management";
    public static final String HEALTH_READY_PATH = "/health/ready";
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
    private static final ServiceSpecPort MANAGEMENT_SERVICE_SPEC_PORT = ServiceSpecPort.builder()
            .name(MANAGEMENT_PORT_NAME)
            .port(KEYCLOAK_MANAGEMENT_PORT)
            .targetPort("" + KEYCLOAK_MANAGEMENT_PORT)
            .protocol("TCP")
            .build();
    protected final CloudPlatform cloudProvider;
    private final AfonPropertyProvider properties;
    private ITerraformDependable dnsRecord;

    public KeyCloakConfig(
        @NonNull Construct scope,
        @NonNull AfonPropertyProvider properties,
        @NonNull CloudPlatform cloudProvider) {
        super(scope, APP_NAME + "_scope");
        this.cloudProvider = cloudProvider;
        this.properties = properties;

        // Login theme, packaged as classpath resources under afon-theme's
        // themes/<theme>/keycloak/login/ and mounted at /opt/keycloak/themes/<theme>. The realm
        // opts into it via loginTheme (see KeyCloakProvider#createRealm).
        String theme = properties.getValue(AFON_THEME, "emrys-hdrs");
        String themeMount = "/opt/keycloak/themes/" + theme;
        String themeResourcePrefix = "themes/" + theme + "/keycloak/login/";

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
        // Serves /health/ready on the management port, for the load balancer's health checks.
        envs.add(envVar("KC_HEALTH_ENABLED", "true"));
        envs.add(envVar(ENV_KC_HOSTNAME, hostname));
        envs.add(envVar("KC_LOG_LEVEL", "DEBUG"));
        kcProperties.values().forEach(envName -> envs.add(
            envFromSecret(envName, SECRET_NAME, envName))
        );

        // The login theme ships as a ConfigMap of whatever files ThemeResources finds under the
        // theme's keycloak/login/ subtree, mounted into the themes dir KeyCloak scans at startup.
        // ConfigMap keys can't contain '/', so each file's relative path is flattened into a key;
        // `items` then reshapes those flat keys back into the theme's real directory layout. Valid
        // UTF-8 files (theme.properties, css, svg, ...) ride in `data` so they stay readable via
        // kubectl; anything else (images, fonts, ...) rides in `binaryData`.
        Map<String, byte[]> themeFiles = ThemeResources.list(themeResourcePrefix, "theme.properties");
        Map<String, String> themeData = new LinkedHashMap<>();
        Map<String, String> themeBinaryData = new LinkedHashMap<>();
        List<DeploymentSpecTemplateSpecVolumeConfigMapItems> themeItems = new ArrayList<>();
        themeFiles.forEach((relativePath, bytes) -> {
            String key = relativePath.replace('/', '_');
            String text = decodeUtf8(bytes);
            if (text != null) {
                themeData.put(key, text);
            } else {
                themeBinaryData.put(key, Base64.getEncoder().encodeToString(bytes));
            }
            themeItems.add(themeItem(key, "login/" + relativePath));
        });

        ConfigMap themeCm = ConfigMap.Builder
            .create(scope, "keycloakTheme")
            .metadata(ConfigMapMetadata
                          .builder()
                          .name(THEME_CONFIGMAP)
                          .namespace("default")
                          .build())
            .data(themeData)
            .binaryData(themeBinaryData)
            .build();

        DeploymentSpecTemplateSpecVolume themeVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name("theme")
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap
                           .builder()
                           .name(THEME_CONFIGMAP)
                           .items(themeItems)
                           .build())
            .build();

        List<String> args = List.of("start");
        // Without it the pod is behind its Service as soon as its container starts, but KeyCloak takes a minute or more
        // to serve, so whatever asks it meanwhile, eg the setup of the OIDC users or Polaris discovering its issuer, is
        // answered 502 by the ingress, or 503 "Bootstrap in progress" by KeyCloak itself.  A failing probe only keeps the
        // pod out of the Service, it is never restarted for it.
        // The probe asks for what the pod is for, ie to serve the realm, rather than for /health/ready, whose "Keycloak
        // cluster health check" went DOWN on a single healthy instance a minute after its start, 503 from then on, so that
        // the probe took a KeyCloak that served every request out of its Service.
        DeploymentSpecTemplateSpecContainerReadinessProbe readinessProbe = DeploymentSpecTemplateSpecContainerReadinessProbe
            .builder()
            .httpGet(DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet
                         .builder()
                         .path("/realms/master")
                         .port("" + KEYCLOAK_PORT)
                         .build())
            .initialDelaySeconds(15)
            .periodSeconds(5)
            .timeoutSeconds(3)
            .failureThreshold(36)
            .build();
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(DockerImages.KEYCLOAK_IMAGE)
            .args(args)
            .env(envs)
            .readinessProbe(readinessProbe)
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name("theme")
                                     .mountPath(themeMount)
                                     .readOnly(true)
                                     .build()))
            .build();
        List<ITerraformDependable> dependants = new ArrayList(cloudProvider.getDependables());
        dependants.add(kcSecrets);
        dependants.add(themeCm);
        Map<String, String> labels = Map.of("app", APP_NAME);
        Deployment app = CdkUtils.deploy(
            this,
            dependants,
            labels,
            List.of(keyCloak),
            List.of(themeVolume),
            APP_NAME);

        dependants.add(app);
        // The Service must not be created until the load balancer controller's mutating webhook is
        // serving (on AWS its failurePolicy=Fail rejects the Service otherwise); getServiceDependables
        // is empty on clouds without such a webhook.
        dependants.addAll(cloudProvider.getServiceDependables());
        // NodePort (not ClusterIP): the AWS Load Balancer Controller uses instance targets by
        // default and requires a node port to build the ingress TargetGroup.
        // The management port is exposed too, so the load balancer can reach the health checks.
        nodePortService(dependants, labels, KEYCLOAK_SERVICE_NAME,
                        List.of(SERVICE_SPEC_PORT, MANAGEMENT_SERVICE_SPEC_PORT), this);
    }

    private static DeploymentSpecTemplateSpecVolumeConfigMapItems themeItem(String key, String path) {
        return DeploymentSpecTemplateSpecVolumeConfigMapItems
            .builder()
            .key(key)
            .path(path)
            .build();
    }

    /**
     * {@code bytes} decoded as UTF-8, or {@code null} if it isn't valid UTF-8 — unlike
     * {@code new String(bytes, UTF_8)}, which silently replaces malformed input instead of
     * signalling it, so it can't be used to detect binary files.
     */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Exposes KeyCloak over HTTPS at {@code OIDC_DOMAIN_NAME}, and makes it resolvable.
     */
    public void configureHttpsAndDNS() {
        dnsRecord = cloudProvider.exposeHttps(new HttpsRoute(
            "keycloak", properties.getValue(OIDC_DOMAIN_NAME), KEYCLOAK_SERVICE_NAME, KEYCLOAK_PORT,
            KEYCLOAK_INGRESS_NAME, new HealthCheck(HEALTH_READY_PATH, MANAGEMENT_PORT_NAME),
            "keycloak-tls", true, List.of(), Optional.empty())).dnsRecord();
    }

    /**
     * The DNS record that makes the KeyCloak hostname, and hence the OIDC issuer, resolvable.
     * Null until {@link #configureHttpsAndDNS()} has run.  Anything that performs OIDC discovery at
     * startup should depend on this, or it may be created while the hostname still does not exist.
     */
    public ITerraformDependable getDnsRecord() {
        return dnsRecord;
    }
}
