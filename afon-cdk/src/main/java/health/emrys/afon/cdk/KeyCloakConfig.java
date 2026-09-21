package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
     * Emrys Health login theme (HDRS palette), packaged as classpath resources
     * under {@code keycloak-theme/emrys-hdrs/} and mounted at
     * {@code /opt/keycloak/themes/emrys-hdrs}. The realm opts into it via
     * {@code loginTheme} (see {@code KeyCloakProvider#createRealm}).
     */
    private static final String THEME_NAME      = "emrys-hdrs";
    private static final String THEME_CONFIGMAP = "keycloak-theme";
    private static final String THEME_MOUNT     = "/opt/keycloak/themes/" + THEME_NAME;
    /** Classpath root the theme files are packaged under. */
    private static final String THEME_RESOURCE_ROOT = "/keycloak-theme/" + THEME_NAME + "/login/";
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

        // The emrys-hdrs login theme ships as a ConfigMap of classpath resources,
        // mounted into the themes dir KeyCloak scans at startup. Text files ride in
        // `data`, binaries (logos, favicon) in `binaryData`; `items` reshapes the
        // flat keys into the theme's directory layout.
        ConfigMap theme = ConfigMap.Builder
            .create(scope, "keycloakTheme")
            .metadata(ConfigMapMetadata
                          .builder()
                          .name(THEME_CONFIGMAP)
                          .namespace("default")
                          .build())
            .data(Map.of(
                "theme.properties", resourceText(THEME_RESOURCE_ROOT + "theme.properties"),
                "hdrs.css",         resourceText(THEME_RESOURCE_ROOT + "resources/css/hdrs.css"),
                "hdrs-dots.svg",    resourceText(THEME_RESOURCE_ROOT + "resources/img/hdrs-dots.svg")))
            .binaryData(Map.of(
                "emrys-logo-horizontal-white.png",
                    resourceBase64(THEME_RESOURCE_ROOT + "resources/img/emrys-logo-horizontal-white.png"),
                "hdrs-logo-horizontal-light.png",
                    resourceBase64(THEME_RESOURCE_ROOT + "resources/img/hdrs-logo-horizontal-light.png"),
                "favicon.ico",
                    resourceBase64(THEME_RESOURCE_ROOT + "resources/img/favicon.ico")))
            .build();

        DeploymentSpecTemplateSpecVolume themeVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name("theme")
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap
                           .builder()
                           .name(THEME_CONFIGMAP)
                           .items(List.of(
                               themeItem("theme.properties", "login/theme.properties"),
                               themeItem("hdrs.css", "login/resources/css/hdrs.css"),
                               themeItem("hdrs-dots.svg", "login/resources/img/hdrs-dots.svg"),
                               themeItem("emrys-logo-horizontal-white.png",
                                         "login/resources/img/emrys-logo-horizontal-white.png"),
                               themeItem("hdrs-logo-horizontal-light.png",
                                         "login/resources/img/hdrs-logo-horizontal-light.png"),
                               themeItem("favicon.ico", "login/resources/img/favicon.ico")))
                           .build())
            .build();

        List<String> args = List.of("start");
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(KEYCLOAK_IMAGE)
            .args(args)
            .env(envs)
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name("theme")
                                     .mountPath(THEME_MOUNT)
                                     .readOnly(true)
                                     .build()))
            .build();
        List<ITerraformDependable> dependants = new ArrayList(cloudProvider.getDependables());
        dependants.add(kcSecrets);
        dependants.add(theme);
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
        nodePortService(dependants, labels, KEYCLOAK_SERVICE_NAME, List.of(SERVICE_SPEC_PORT), this);
    }

    private static DeploymentSpecTemplateSpecVolumeConfigMapItems themeItem(String key, String path) {
        return DeploymentSpecTemplateSpecVolumeConfigMapItems
            .builder()
            .key(key)
            .path(path)
            .build();
    }

    private static String resourceText(String path) {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private static String resourceBase64(String path) {
        return Base64.getEncoder().encodeToString(resourceBytes(path));
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream in = KeyCloakConfig.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("KeyCloak theme resource not on classpath: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read KeyCloak theme resource " + path, e);
        }
    }

    protected abstract void configureHttpsAndDNS();
}
