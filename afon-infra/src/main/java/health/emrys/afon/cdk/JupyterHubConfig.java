package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpec;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecSelector;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplate;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpec;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFrom;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerPort;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.role.Role;
import com.hashicorp.cdktf.providers.kubernetes.role.RoleMetadata;
import com.hashicorp.cdktf.providers.kubernetes.role.RoleRule;
import com.hashicorp.cdktf.providers.kubernetes.role_binding.RoleBinding;
import com.hashicorp.cdktf.providers.kubernetes.role_binding.RoleBindingMetadata;
import com.hashicorp.cdktf.providers.kubernetes.role_binding.RoleBindingRoleRef;
import com.hashicorp.cdktf.providers.kubernetes.role_binding.RoleBindingSubject;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.config.AfonProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.DeploymentUtils.withAppKubernetesIoName;
import static health.emrys.afon.cdk.TFUtils.envVar;

/**
 * Deploys <a href="https://z2jh.jupyter.org/">JupyterHub</a> instead of the single shared
 * Jupyter pod that {@link JupyterConfig} stands up.
 *
 * <p>Whereas {@link JupyterConfig} runs one notebook server that every user shares, JupyterHub
 * gives each authenticated user their own single-user notebook <em>pod</em>, spawned on demand by
 * {@code KubeSpawner} and torn down when idle. This trades the static VNC/Guacamole wiring of the
 * single-pod design for dynamic per-user isolation.
 *
 * <p>Three long-lived components are deployed (mirroring the upstream Zero-to-JupyterHub layout,
 * but as plain Kubernetes resources rather than the Helm chart so it slots into the existing CDKTF
 * stacks):
 * <ul>
 *   <li>{@code hub} — the JupyterHub control plane. Authenticates users (delegated to the
 *       oauth2-proxy gate in front, see below) and asks {@code KubeSpawner} to create one notebook
 *       pod per user. Listens on 8081.</li>
 *   <li>{@code proxy} — {@code configurable-http-proxy}. The public entrypoint (8000); routes
 *       {@code /hub} traffic to the hub and {@code /user/<name>} traffic to that user's notebook
 *       pod. Its routing table is reconfigured live by the hub over the API port (8001).</li>
 *   <li>per-user notebook pods — created at runtime by {@code KubeSpawner}, not declared here.</li>
 * </ul>
 *
 * <p>As with {@link JupyterConfig}, authentication is fronted by oauth2-proxy (so an OIDC provider
 * enforces MFA); the hub itself therefore uses a pass-through authenticator. oauth2-proxy points at
 * the {@code proxy-public} ClusterIP service rather than at a single Jupyter pod.
 */
public abstract class JupyterHubConfig {

    private static final String HUB_IMAGE     = "jupyterhub/k8s-hub:3.3.8";
    private static final String PROXY_IMAGE   = "jupyterhub/configurable-http-proxy:4.6.1";
    /** Same multi-VNC notebook image the single-pod {@link JupyterConfig} uses. */
    private static final String SINGLEUSER_IMAGE =
        "ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi";

    private static final int HUB_PORT        = 8081;
    private static final int PROXY_PUBLIC    = 8000;
    private static final int PROXY_API       = 8001;
    private static final int SINGLEUSER_PORT = 8888;

    private static final String HUB_SVC          = "hub";
    private static final String PROXY_PUBLIC_SVC = "proxy-public";

    /** In-cluster URL oauth2-proxy should forward authenticated traffic to (JupyterHub's public proxy). */
    public static final String PUBLIC_UPSTREAM_URL = "http://" + PROXY_PUBLIC_SVC + ":" + PROXY_PUBLIC;
    private static final String PROXY_API_SVC    = "proxy-api";
    private static final String CONFIG_MAP_NAME  = "hub-config";
    private static final String CONFIG_FILE      = "jupyterhub_config.py";
    private static final String CONFIG_MOUNT     = "/etc/jupyterhub";
    private static final String SECRET_NAME      = "jupyterhub-secrets";

    private static final Map<String, String> HUB_LABELS   = Map.of("app", "jupyterhub-hub");
    private static final Map<String, String> PROXY_LABELS = Map.of("app", "jupyterhub-proxy");

    private final   TerraformStack stack;
    protected final Properties     properties;

    public JupyterHubConfig(TerraformStack stack,
                            Properties properties) {
        this.stack      = stack;
        this.properties = properties;
    }

    public Map<String, String> deployJupyterHub(List<ITerraformDependable> eksDependOn) {
        // KubeSpawner (running inside the hub pod) needs to create/watch/delete the per-user
        // notebook pods, the events they emit and the secrets/PVCs it mounts into them. Scope the
        // permission to the namespace with a Role + ServiceAccount rather than a ClusterRole.
        ServiceAccount hubServiceAccount = ServiceAccount.Builder
            .create(stack, "jupyterhubServiceAccount")
            .metadata(ServiceAccountMetadata.builder()
                                            .name(HUB_SVC)
                                            .namespace("default")
                                            .build())
            .dependsOn(eksDependOn)
            .build();

        Role hubRole = Role.Builder
            .create(stack, "jupyterhubRole")
            .metadata(RoleMetadata.builder()
                                  .name(HUB_SVC)
                                  .namespace("default")
                                  .build())
            .rule(List.of(
                RoleRule.builder()
                        .apiGroups(List.of(""))
                        .resources(List.of("pods", "persistentvolumeclaims", "secrets", "services"))
                        .verbs(List.of("get", "watch", "list", "create", "delete"))
                        .build(),
                RoleRule.builder()
                        .apiGroups(List.of(""))
                        .resources(List.of("events"))
                        .verbs(List.of("get", "watch", "list"))
                        .build()))
            .dependsOn(eksDependOn)
            .build();

        RoleBinding hubRoleBinding = RoleBinding.Builder
            .create(stack, "jupyterhubRoleBinding")
            .metadata(RoleBindingMetadata.builder()
                                         .name(HUB_SVC)
                                         .namespace("default")
                                         .build())
            .roleRef(RoleBindingRoleRef.builder()
                                       .apiGroup("rbac.authorization.k8s.io")
                                       .kind("Role")
                                       .name(hubRole.getMetadata().getName())
                                       .build())
            .subject(List.of(RoleBindingSubject.builder()
                                               .kind("ServiceAccount")
                                               .name(hubServiceAccount.getMetadata().getName())
                                               .namespace("default")
                                               .build()))
            .dependsOn(List.of(hubRole, hubServiceAccount))
            .build();

        // The hub and the proxy authenticate to each other with a shared token, and the hub signs
        // its cookies with a fixed secret so restarts don't log everyone out. Derive both
        // deterministically from an existing secret so no new configuration is required.
        String cookieSecret = AfonProperties.OAUTH2_COOKIE_SECRET.getValueFrom(properties);
        Secret hubSecret = Secret.Builder
            .create(stack, "jupyterhubSecret")
            .metadata(SecretMetadata.builder()
                                    .name(SECRET_NAME)
                                    .namespace("default")
                                    .build())
            .data(Map.of(
                "proxy.token", sha256Hex("proxy-token:" + cookieSecret),
                "hub.cookieSecret", sha256Hex("cookie-secret:" + cookieSecret)))
            .dependsOn(eksDependOn)
            .build();

        ConfigMap hubConfig = ConfigMap.Builder
            .create(stack, "jupyterhubConfigMap")
            .metadata(ConfigMapMetadata.builder()
                                       .name(CONFIG_MAP_NAME)
                                       .namespace("default")
                                       .build())
            .data(Map.of(CONFIG_FILE, jupyterhubConfigPy()))
            .dependsOn(eksDependOn)
            .build();

        List<ITerraformDependable> hubDeps = new ArrayList<>(eksDependOn);
        hubDeps.add(hubRoleBinding);
        hubDeps.add(hubSecret);
        hubDeps.add(hubConfig);
        deployHub(hubDeps);

        List<ITerraformDependable> proxyDeps = new ArrayList<>(eksDependOn);
        proxyDeps.add(hubSecret);
        deployProxy(proxyDeps);

        // Public entrypoint the oauth2-proxy MFA gate forwards to (in place of a single Jupyter pod).
        Service proxyPublic = clusterIpService(
            "jupyterhubProxyPublic", PROXY_PUBLIC_SVC, PROXY_LABELS, "http", PROXY_PUBLIC, eksDependOn);

        // Put oauth2-proxy in front of the public proxy so MFA is enforced before anything reaches
        // JupyterHub, exactly as JupyterConfig does for the single Jupyter pod.
        List<ITerraformDependable> mfaDeps = new ArrayList<>(eksDependOn);
        mfaDeps.add(proxyPublic);
        getMFAConfig(mfaDeps);

        configureHttpsAndDNS();

        return PROXY_LABELS;
    }

    private void deployHub(List<ITerraformDependable> dependsOn) {
        DeploymentSpecTemplateSpecContainer hubContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("hub")
            .image(HUB_IMAGE)
            .command(List.of("jupyterhub"))
            .args(List.of("--config", CONFIG_MOUNT + "/" + CONFIG_FILE))
            .env(List.of(
                envFromSecret("CONFIGPROXY_AUTH_TOKEN", SECRET_NAME, "proxy.token"),
                envFromSecret("JPY_COOKIE_SECRET", SECRET_NAME, "hub.cookieSecret")))
            .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                 .containerPort(HUB_PORT)
                                                                 .protocol("TCP")
                                                                 .build()))
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name("config")
                                     .mountPath(CONFIG_MOUNT)
                                     .readOnly(true)
                                     .build()))
            .build();

        DeploymentSpecTemplateSpecVolume configVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name("config")
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                .name(CONFIG_MAP_NAME)
                                                                .build())
            .build();

        // Built directly (rather than via DeploymentUtils.deploy) so the pod runs under the `hub`
        // ServiceAccount that grants KubeSpawner its pod-management permissions.
        Deployment.Builder
            .create(stack, "jupyterhubHubDeployment")
            .dependsOn(dependsOn)
            .metadata(DeploymentMetadata.builder()
                                        .name("hub-deployment")
                                        .labels(HUB_LABELS)
                                        .build())
            .spec(DeploymentSpec.builder()
                                .replicas("1")
                                .selector(DeploymentSpecSelector.builder()
                                                                .matchLabels(HUB_LABELS)
                                                                .build())
                                .template(DeploymentSpecTemplate
                                              .builder()
                                              .metadata(DeploymentSpecTemplateMetadata
                                                            .builder()
                                                            .labels(withAppKubernetesIoName(HUB_LABELS))
                                                            .build())
                                              .spec(DeploymentSpecTemplateSpec
                                                        .builder()
                                                        .serviceAccountName(HUB_SVC)
                                                        .container(List.of(hubContainer))
                                                        .volume(List.of(configVolume))
                                                        .build())
                                              .build())
                                .build())
            .build();

        clusterIpService("jupyterhubHub", HUB_SVC, HUB_LABELS, "hub", HUB_PORT, dependsOn);
    }

    private void deployProxy(List<ITerraformDependable> dependsOn) {
        // configurable-http-proxy: 8000 is the public listener, 8001 is the API the hub drives to
        // (re)configure routes. The shared token must match the hub's CONFIGPROXY_AUTH_TOKEN.
        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("chp")
            .image(PROXY_IMAGE)
            .command(List.of("configurable-http-proxy"))
            .args(List.of(
                "--ip=0.0.0.0",
                "--port=" + PROXY_PUBLIC,
                "--api-ip=0.0.0.0",
                "--api-port=" + PROXY_API,
                "--default-target=http://" + HUB_SVC + ":" + HUB_PORT,
                "--error-target=http://" + HUB_SVC + ":" + HUB_PORT + "/hub/error"))
            .env(List.of(envFromSecret("CONFIGPROXY_AUTH_TOKEN", SECRET_NAME, "proxy.token")))
            .port(List.of(
                DeploymentSpecTemplateSpecContainerPort.builder()
                                                       .containerPort(PROXY_PUBLIC)
                                                       .protocol("TCP")
                                                       .build(),
                DeploymentSpecTemplateSpecContainerPort.builder()
                                                       .containerPort(PROXY_API)
                                                       .protocol("TCP")
                                                       .build()))
            .build();

        DeploymentUtils.deploy(stack, dependsOn, PROXY_LABELS, List.of(proxyContainer), List.of(), "jupyterhub-proxy");

        clusterIpService("jupyterhubProxyApi", PROXY_API_SVC, PROXY_LABELS, "api", PROXY_API, dependsOn);
    }

    private Service clusterIpService(String id,
                                     String name,
                                     Map<String, String> selector,
                                     String portName,
                                     int port,
                                     List<ITerraformDependable> dependsOn) {
        return Service.Builder
            .create(stack, id)
            .metadata(ServiceMetadata.builder()
                                     .name(name)
                                     .namespace("default")
                                     .build())
            .dependsOn(dependsOn)
            .spec(ServiceSpec.builder()
                             .selector(selector)
                             .type("ClusterIP")
                             .port(List.of(ServiceSpecPort.builder()
                                                          .name(portName)
                                                          .port(port)
                                                          .targetPort(String.valueOf(port))
                                                          .protocol("TCP")
                                                          .build()))
                             .build())
            .build();
    }

    /**
     * The hub's runtime configuration. Points KubeSpawner at the same notebook image the single-pod
     * deployment uses, tells the hub how to reach the externally-run proxy, and — since oauth2-proxy
     * is the real authentication gate — lets any username straight through.
     */
    private static String jupyterhubConfigPy() {
        return """
            import os

            c = get_config()  # noqa

            # --- Proxy runs in its own pod (configurable-http-proxy), not inside the hub. ---
            c.ConfigurableHTTPProxy.should_start = False
            c.ConfigurableHTTPProxy.api_url = 'http://%1$s:%2$d'

            # --- How spawned notebook pods reach the hub. ---
            c.JupyterHub.hub_ip = '0.0.0.0'
            c.JupyterHub.hub_connect_ip = '%3$s'
            c.JupyterHub.hub_port = %4$d
            c.JupyterHub.cleanup_servers = False

            # --- One pod per user, spawned on demand by KubeSpawner. ---
            c.JupyterHub.spawner_class = 'kubespawner.KubeSpawner'
            c.KubeSpawner.namespace = os.environ.get('POD_NAMESPACE', 'default')
            c.KubeSpawner.image = '%5$s'
            c.KubeSpawner.port = %6$d
            c.KubeSpawner.start_timeout = 300
            c.KubeSpawner.http_timeout = 120
            # One in-pod VNC session per single-user server (see start-vnc.sh in the image).
            c.KubeSpawner.environment = {'VNC_SESSIONS': '1'}

            # --- oauth2-proxy in front already enforces auth/MFA, so pass usernames through. ---
            c.JupyterHub.authenticator_class = 'jupyterhub.auth.DummyAuthenticator'
            c.Authenticator.allow_all = True
            """.formatted(
            PROXY_API_SVC, PROXY_API,
            HUB_SVC, HUB_PORT,
            SINGLEUSER_IMAGE, SINGLEUSER_PORT);
    }

    private static DeploymentSpecTemplateSpecContainerEnv envFromSecret(
        String envName, String secretName, String secretKey) {
        return DeploymentSpecTemplateSpecContainerEnv
            .builder()
            .name(envName)
            .valueFrom(DeploymentSpecTemplateSpecContainerEnvValueFrom
                           .builder()
                           .secretKeyRef(DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef
                                             .builder()
                                             .name(secretName)
                                             .key(secretKey)
                                             .build())
                           .build())
            .build();
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                                         .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    protected abstract void configureHttpsAndDNS();

    protected abstract JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps);
}