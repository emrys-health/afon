package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
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
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import org.slf4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static health.emrys.afon.cdk.CdkUtils.withAppKubernetesIoName;
import static health.emrys.afon.cdk.CdkHelper.clusterIpService;
import static health.emrys.afon.cdk.CdkHelper.envFromSecret;
import static org.slf4j.LoggerFactory.getLogger;

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

    private static final Logger logger    = getLogger(JupyterHubConfig.class);

    private static final String HUB_IMAGE = "jupyterhub/k8s-hub:3.3.8";
    private static final String PROXY_IMAGE      = "jupyterhub/configurable-http-proxy:4.6.1";
    /**
     * Same multi-VNC notebook image the single-pod {@link JupyterConfig} uses.
     */
    private static final String SINGLEUSER_IMAGE =
        "ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi";

    private static final int HUB_PORT        = 8081;
    private static final int PROXY_PUBLIC    = 8000;
    private static final int PROXY_API       = 8001;
    /**
     * Port the hub-facing {@code jupyterhub-singleuser} server binds to. It is NOT 8888: that is
     * reserved for the plain, token-less Jupyter the image runs on 127.0.0.1:8888 for the user to
     * work in via the VNC desktop ({@code http://localhost:8888}). The hub server exists only so
     * the hub considers the pod healthy; users never reach it (its public path is blocked).
     */
    private static final int SINGLEUSER_PORT = 8090;
    /**
     * Port the in-pod VNC server listens on (display :1) — Guacamole connects here per user.
     */
    private static final int VNC_PORT        = 5901;

    private static final String HUB_SVC          = "hub";
    private static final String PROXY_PUBLIC_SVC = "proxy-public";
    private static final String PROXY_API_SVC    = "proxy-api";
    private static final String CONFIG_MAP_NAME  = "hub-config";
    private static final String CONFIG_FILE      = "jupyterhub_config.py";
    private static final String CONFIG_MOUNT     = "/etc/jupyterhub";
    /**
     * HDRS Patch 16. The perimeter sidecar's image references live in their own tiny
     * ConfigMap, mounted beside the hub config and read fresh at every spawn - so the
     * audit platform's Deploy workflow can roll the sensor version with one
     * {@code kubectl patch}, with no rebuild, no synth, no tofu apply and no hub restart.
     * Tofu owns the ConfigMap's existence and ignores its data for the same reason
     * the platform's Terraform ignores the auditor's image: the version changes often
     * and fast, and a later apply must never quietly roll it back.
     */
    private static final String IMAGES_CM_NAME   = "perimeter-images";
    private static final String IMAGES_MOUNT     = CONFIG_MOUNT + "/" + IMAGES_CM_NAME;
    private static final String SECRET_NAME      = "jupyterhub-secrets";

    /**
     * JupyterHub owns the domain root so that a bare visit to the domain lands on the hub, which
     * auto-spawns the user's pod and then hands off to Guacamole (served under {@link #GUACAMOLE_PATH})
     * to stream that pod's desktop.
     */
    private static final String HUB_BASE_URL    = "/";
    /**
     * Sub-path Guacamole is served under (its default servlet context) now that the hub owns root.
     * The post-spawn redirect and the oauth2-proxy upstream both point here.
     */
    private static final String GUACAMOLE_PATH  = "/guacamole/";
    /**
     * KubeSpawner stamps this label (value = the user's slug) on each pod; the per-user VNC Service selects it.
     */
    private static final String GUACAMOLE_USER = "guacamole-user";

    /**
     * Label KubeSpawner stamps on every notebook pod it spawns (see {@code extra_labels} in
     * {@link #jupyterhubConfigPy()}). It is the handle the egress {@code NetworkPolicy} selects on:
     * the pods that need locking down are the per-user notebooks — where user code actually runs —
     * not the hub/proxy control plane. Those pods do not exist at synth time, so a label the spawner
     * applies is the only way to write a policy for them up front.
     */
    private static final String SINGLEUSER_LABEL_KEY   = "afon-role";
    private static final String SINGLEUSER_LABEL_VALUE = "jupyter-singleuser";

    /**
     * The same label, as a Kubernetes label selector - for anything that has to select the pods
     * this class stamps rather than stamp them itself.
     *
     * <p>Exposed rather than retyped because this string is now load-bearing in three places: the
     * egress {@code NetworkPolicy}, the pods themselves, and the audit platform's exec sensor,
     * which exports events only for pods matching it (HDRS Patch 20). It has been wrong once
     * already - {@code deployJupyterHub} returned the proxy's labels rather than the notebook's,
     * and a workspace everyone believed was default-denied was restricted by nothing. A third
     * consumer copying the literal, or reading it from a properties file no compiler checks,
     * would make the same failure available again and silent: a selector matching nothing installs
     * and runs and reports on no one.
     */
    public static String singleuserSelector() {
        return SINGLEUSER_LABEL_KEY + "=" + SINGLEUSER_LABEL_VALUE;
    }


    /**
     * Upstreams the oauth2-proxy MFA gate fronts, on a single domain, both behind the same OIDC/MFA
     * gate. oauth2-proxy routes by longest path prefix: {@code /guacamole/*} hits Guacamole and
     * everything else falls through to the JupyterHub public proxy (the hub at {@code /hub/*} and the
     * spawned single-user notebooks at {@code /user/*}). The hub owns the root so a bare visit to the
     * domain lands on it and auto-spawns the user's pod before handing off to Guacamole.
     */
    public static final List<String> MFA_UPSTREAMS = List.of(
        "http://" + GuacamoleConfig.SVC_NAME + ":" + GuacamoleConfig.WEB_PORT + GUACAMOLE_PATH,
        "http://" + PROXY_PUBLIC_SVC + ":" + PROXY_PUBLIC + HUB_BASE_URL);

    /**
     * Package-visible: {@link NetworkConfig} selects on these to allow the spawned notebook pods
     * to reach the hub. A single-user server that cannot reach the hub fails to register and the
     * spawn times out, so this peer is mandatory, not optional.
     */
    static final Map<String, String> HUB_LABELS   = Map.of("app", "jupyterhub-hub");
    private static final Map<String, String> PROXY_LABELS = Map.of("app", "jupyterhub-proxy");
    /**
     * Selector for the pods KubeSpawner spawns, matching the labels it stamps on them.
     */
    private static final Map<String, String> SINGLEUSER_LABELS =
        Map.of(SINGLEUSER_LABEL_KEY, SINGLEUSER_LABEL_VALUE);

    private final   TerraformStack                stack;
    private final   String                        jupyterPassword;
    private final   List<GuacamoleConfig.Session> sessions;
    private final   AfonUserProvider              userProvider = new FileUserProvider();
    protected final   AfonPropertyProvider        properties;

    public JupyterHubConfig(
        TerraformStack stack,
        AfonPropertyProvider properties) {
        this.stack           = stack;
        this.properties      = properties;
        this.jupyterPassword = properties.getValue(AfonProperty.JUPYTER_VNC_PASSWORD);
        // One Guacamole login per user, keyed by email; each streams that user's own pod desktop on
        // the single VNC port (each user now has their own pod rather than sharing ports on one).
        this.sessions = new ArrayList<>();
        for (AfonUser user : userProvider.getUsers()) {
            sessions.add(new GuacamoleConfig.Session(user.email(), jupyterPassword, VNC_PORT));
        }
    }

    /**
     * @return the pod selector the egress {@code NetworkPolicy} should be written against — the
     *         labels on the per-user notebook pods KubeSpawner spawns, not on the hub or proxy.
     */
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

        // Secrets the hub needs:
        //  - proxy.token / hub.cookieSecret: shared with the proxy and used to sign cookies;
        //    derived deterministically from an existing secret so restarts don't log everyone out.
        //  - hub.cryptKey: encrypts auth_state (the captured OIDC tokens) at rest in the hub DB.
        //  - oauth.clientId / oauth.clientSecret: the OIDC client credentials (same provider as
        //    oauth2-proxy) JupyterHub uses to log users in and obtain their tokens.
        String cookieSecret = properties.getValue(AfonProperty.OAUTH2_COOKIE_SECRET);
        Secret hubSecret = Secret.Builder
            .create(stack, "jupyterhubSecret")
            .metadata(SecretMetadata.builder()
                                    .name(SECRET_NAME)
                                    .namespace("default")
                                    .build())
            .data(Map.of(
                "proxy.token", sha256Hex("proxy-token:" + cookieSecret),
                "hub.cookieSecret", sha256Hex("cookie-secret:" + cookieSecret),
                "hub.cryptKey", sha256Hex("crypt-key:" + cookieSecret),
                "oauth.clientId", oauthClientId(),
                "oauth.clientSecret", oauthClientSecret(),
                //  - vnc.password: the password x11vnc is started with inside each workspace pod,
                //    and the one Guacamole presents when it connects. It reaches the pod through
                //    the hub, which puts it in the spawned pod's environment, so it lives here
                //    rather than in the hub ConfigMap - a ConfigMap is readable by anyone holding
                //    get on ConfigMaps.
                "vnc.password", jupyterPassword))
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

        // HDRS Patch 16: the sidecar image references, patchable at runtime. Data is
        // ignored after creation so the Deploy workflow's `kubectl patch` and a later
        // tofu apply never fight - the same shape/version split the platform's own
        // Terraform uses for the auditor Deployment.
        ConfigMap perimeterImages = ConfigMap.Builder
            .create(stack, "perimeterImagesConfigMap")
            .metadata(ConfigMapMetadata.builder()
                                       .name(IMAGES_CM_NAME)
                                       .namespace("default")
                                       .build())
            .data(Map.of(
                "AUDIT_SIDECAR_IMAGE", properties.getValue(AfonProperty.AUDIT_SIDECAR_IMAGE, ""),
                "AUDIT_REDIRECT_IMAGE", properties.getValue(AfonProperty.AUDIT_REDIRECT_IMAGE, "")))
            .lifecycle(TerraformResourceLifecycle.builder()
                                                 .ignoreChanges(List.of("data"))
                                                 .build())
            .dependsOn(eksDependOn)
            .build();

        // What lets the audit platform's CI touch it: the deploy role's EKS access is
        // scoped to the `audit` namespace, and this Role grants exactly one object in
        // `default` on top of that - get and patch on the ConfigMap above, by name.
        Role perimeterImagesRole = Role.Builder
            .create(stack, "perimeterImagesRole")
            .metadata(RoleMetadata.builder()
                                  .name("hdrs-audit-deployer-perimeter-images")
                                  .namespace("default")
                                  .build())
            .rule(List.of(RoleRule.builder()
                                  .apiGroups(List.of(""))
                                  .resources(List.of("configmaps"))
                                  .resourceNames(List.of(IMAGES_CM_NAME))
                                  .verbs(List.of("get", "patch"))
                                  .build()))
            .dependsOn(eksDependOn)
            .build();

        RoleBinding.Builder
            .create(stack, "perimeterImagesRoleBinding")
            .metadata(RoleBindingMetadata.builder()
                                         .name("hdrs-audit-deployer-perimeter-images")
                                         .namespace("default")
                                         .build())
            .roleRef(RoleBindingRoleRef.builder()
                                       .apiGroup("rbac.authorization.k8s.io")
                                       .kind("Role")
                                       .name(perimeterImagesRole.getMetadata().getName())
                                       .build())
            .subject(List.of(RoleBindingSubject.builder()
                                               .apiGroup("rbac.authorization.k8s.io")
                                               .kind("Group")
                                               .name("hdrs:audit-deployer")
                                               .build()))
            .dependsOn(eksDependOn)
            .build();

        List<ITerraformDependable> hubDeps = new ArrayList<>(eksDependOn);
        hubDeps.add(hubRoleBinding);
        hubDeps.add(hubSecret);
        hubDeps.add(hubConfig);
        hubDeps.add(perimeterImages);
        deployHub(hubDeps);

        List<ITerraformDependable> proxyDeps = new ArrayList<>(eksDependOn);
        proxyDeps.add(hubSecret);
        deployProxy(proxyDeps);

        // Public entrypoint for the JupyterHub side of the MFA gate (login + spawn).
        List<ServiceSpecPort> ports = List.of(ServiceSpecPort.builder()
                                                             .name("http")
                                                             .port(PROXY_PUBLIC)
                                                             .targetPort(String.valueOf(PROXY_PUBLIC))
                                                             .protocol("TCP")
                                                             .build());
        Service proxyPublic = clusterIpService(eksDependOn, PROXY_LABELS, PROXY_PUBLIC_SVC, ports, stack);

        // Per user, a stable VNC Service that selects whichever pod KubeSpawner spawns for that user
        // (matched on the guacamole-user=<slug> label set by the hub's spawn hook). This is the
        // fixed endpoint Guacamole dials, even though the backing pod only exists once the user has
        // logged into JupyterHub and spawned it.
        Map<String, String>        emailToVncHost = new LinkedHashMap<>();
        List<ITerraformDependable> guacamoleDeps  = new ArrayList<>(eksDependOn);
        for (AfonUser user : userProvider.getUsers()) {
            emailToVncHost.put(user.email(), vncServiceName(user.slug()));
            List<ServiceSpecPort> userPorts = List.of(ServiceSpecPort.builder()
                                                        .name("vnc")
                                                        .port(VNC_PORT)
                                                        .targetPort(String.valueOf(VNC_PORT))
                                                        .protocol("TCP")
                                                        .build());
            Map<String, String> guacUserConfig = Map.of(GUACAMOLE_USER, user.slug());
            guacamoleDeps.add(clusterIpService(eksDependOn, guacUserConfig, vncServiceName(user.slug()), userPorts, stack));
        }

        // Guacamole streams each user's pod desktop. Transparent SSO: no Guacamole login page — the
        // guacamole-auth-header extension trusts the X-Forwarded-Email header oauth2-proxy sets, and
        // guacamole-auth-jdbc (backed by the shared RDS Postgres) serves each user their own
        // connection. A one-shot Job creates the DB/schema and provisions one VNC connection per user,
        // each targeting that user's own VNC Service (a different host per user).
        GuacamoleConfig.DbConfig guacamoleDb = new GuacamoleConfig.DbConfig(
            properties.getValue(AfonProperty.POLARIS_DB_HOST),
            5432,
            "guacamole",
            properties.getValue(AfonProperty.KC_DB_USERNAME),
            properties.getValue(AfonProperty.KC_DB_PASSWORD));
        new GuacamoleConfig(
            stack,
            guacamoleDeps,
            session -> emailToVncHost.get(session.username()),
            jupyterPassword,
            sessions,
            guacamoleDb,
            "X-Forwarded-Email");

        // A single oauth2-proxy MFA gate fronts both Guacamole (root) and JupyterHub
        // (under /jupyter/), so users authenticate once, spawn their pod via the hub, then reach its
        // desktop through Guacamole — all behind the same OIDC/MFA login.
        List<ITerraformDependable> mfaDeps = new ArrayList<>(eksDependOn);
        mfaDeps.add(proxyPublic);
        getMFAConfig(mfaDeps);

        configureHttpsAndDNS();

        return SINGLEUSER_LABELS;
    }

    private static String vncServiceName(String slug) {
        return "guac-vnc-" + slug;
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
                envFromSecret("JPY_COOKIE_SECRET", SECRET_NAME, "hub.cookieSecret"),
                envFromSecret("JUPYTERHUB_CRYPT_KEY", SECRET_NAME, "hub.cryptKey"),
                envFromSecret("OAUTH_CLIENT_ID", SECRET_NAME, "oauth.clientId"),
                envFromSecret("OAUTH_CLIENT_SECRET", SECRET_NAME, "oauth.clientSecret"),
                // Not used by the hub itself - forwarded into each spawned workspace so x11vnc and
                // Guacamole agree on a password. See jupyterhubConfigPy().
                envFromSecret("JUPYTER_VNC_PASSWORD", SECRET_NAME, "vnc.password")))
            .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                 .containerPort(HUB_PORT)
                                                                 .protocol("TCP")
                                                                 .build()))
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name("config")
                                     .mountPath(CONFIG_MOUNT)
                                     .readOnly(true)
                                     .build(),
                                 // Patch 16: each key becomes a file; the kubelet syncs updates
                                 // into the running pod, so a patched image ref is readable here
                                 // with no restart.
                                 DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name(IMAGES_CM_NAME)
                                     .mountPath(IMAGES_MOUNT)
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

        DeploymentSpecTemplateSpecVolume imagesVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name(IMAGES_CM_NAME)
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                .name(IMAGES_CM_NAME)
                                                                // The hub must start even if the CM is
                                                                // absent - the config falls back to the
                                                                // baked refs in jupyterhubConfigPy().
                                                                .optional(true)
                                                                .build())
            .build();

        // Built directly (rather than via CdkUtils.deploy) so the pod runs under the `hub`
        // ServiceAccount that grants KubeSpawner its pod-management permissions.
        Deployment.Builder
            .create(stack, "jupyterhubHubDeployment")
            .dependsOn(dependsOn)
            // Longer than the in-config OIDC discovery poll (see jupyterhubConfigPy), so Terraform is
            // still waiting when the hub finally reaches the issuer instead of giving up underneath it.
            .timeouts(DeploymentTimeouts.builder().create("15m").build())
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
                                                        .volume(List.of(configVolume, imagesVolume))
                                                        .build())
                                              .build())
                                .build())
            .build();

        List<ServiceSpecPort> ports = List.of(ServiceSpecPort.builder()
                                                             .name("hub")
                                                             .port(HUB_PORT)
                                                             .targetPort(String.valueOf(HUB_PORT))
                                                             .protocol("TCP")
                                                             .build());
        clusterIpService(dependsOn, HUB_LABELS, HUB_SVC, ports, stack);
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

        CdkUtils.deploy(stack, dependsOn, PROXY_LABELS, List.of(proxyContainer), List.of(), "jupyterhub-proxy");

        List<ServiceSpecPort> ports = List.of(ServiceSpecPort.builder()
                                                             .name("api")
                                                             .port(PROXY_API)
                                                             .targetPort(String.valueOf(PROXY_API))
                                                             .protocol("TCP")
                                                             .build());
        clusterIpService(dependsOn, PROXY_LABELS, PROXY_API_SVC, ports, stack);
    }

    /**
     * The hub's runtime configuration. Points KubeSpawner at the same notebook image the single-pod
     * deployment uses, tells the hub how to reach the externally-run proxy, and — since oauth2-proxy
     * is the real authentication gate — lets any username straight through.
     */
    private String jupyterhubConfigPy() {
        String issuer = properties.getValue(AfonProperty.OIDC_ISSUER_URL);
        logger.info("login at: https://{}/", jupyterDomain());
        return """
               import os, json, time
               from urllib.request import urlopen

               def _discover(url, deadline_s=600, interval_s=5):
                   # The issuer is a public, ALB-backed hostname whose DNS record only appears once the
                   # load balancer is provisioned — minutes after this pod is first scheduled. Poll in
                   # process rather than letting the exception kill us: a crash here means the container
                   # restarts under CrashLoopBackOff, and once that backoff reaches its 5-minute cap the
                   # hub sits idle long past the point where the issuer became reachable.
                   deadline = time.monotonic() + deadline_s
                   while True:
                       try:
                           return json.load(urlopen(url))
                       except Exception as e:
                           if time.monotonic() >= deadline:
                               raise
                           print('waiting for OIDC issuer ' + url + ': ' + str(e), flush=True)
                           time.sleep(interval_s)

               c = get_config()  # noqa
               
               # --- The hub owns the domain root. ---
               c.JupyterHub.base_url = '%7$s'
               # A bare visit to the domain root auto-spawns the user's pod and then hands off to
               # Guacamole, which streams that pod's VNC desktop. Sending root traffic to the spawn
               # page makes it auto-submit (there is no options form); ?next redirects the browser to
               # Guacamole once the pod is up, rather than to the (blocked) single-user web UI.
               c.JupyterHub.default_url = '/hub/spawn?next=%13$s'
               
               # --- Proxy runs in its own pod (configurable-http-proxy), not inside the hub. ---
               c.ConfigurableHTTPProxy.should_start = False
               c.ConfigurableHTTPProxy.api_url = 'http://%1$s:%2$d'
               
               # --- How spawned notebook pods reach the hub. ---
               c.JupyterHub.hub_ip = '0.0.0.0'
               c.JupyterHub.hub_connect_ip = '%3$s'
               c.JupyterHub.hub_port = %4$d
               c.JupyterHub.cleanup_servers = False
               # /user/* is exposed through oauth2-proxy (the hub owns root), so the hub may redirect to
               # the spawned notebook. In normal use ?next (above) sends the browser to Guacamole first;
               # this is only the fallback when no next is supplied.
               c.JupyterHub.redirect_to_server = True
               
               # --- One pod per user, spawned on demand by KubeSpawner. ---
               c.JupyterHub.spawner_class = 'kubespawner.KubeSpawner'
               c.KubeSpawner.namespace = os.environ.get('POD_NAMESPACE', 'default')
               c.KubeSpawner.image = '%5$s'
               # Re-pull on every spawn so a rebuilt image under the same tag is picked up.
               # IfNotPresent, not Always. The notebook image is ~3 GB and a node carries 20 GB of
               # ephemeral storage shared with Spark scratch, so re-pulling on every spawn pushed a
               # node past its eviction threshold and killed the pod mid-download (observed
               # 2026-08-24: evicted with 1.1 GiB free against a 2 GiB threshold). The cost is that
               # a rebuilt image under the SAME tag is no longer picked up - bump the tag instead.
               c.KubeSpawner.image_pull_policy = 'IfNotPresent'
               c.KubeSpawner.port = %6$d
               # A cold pull of that image took over a minute on an idle node, so 300s left no room
               # for a retry after an eviction: the hub abandoned the spawn and deleted the pod
               # while the pull was still running, and the user was left on a Guacamole connection
               # with nothing behind it.
               c.KubeSpawner.start_timeout = 600
               c.KubeSpawner.http_timeout = 120
               # One in-pod VNC session per single-user server (see start-vnc.sh in the image).
               # One in-pod VNC session per single-user server, and the password Guacamole will
               # present. Both names come from start-vnc.sh in the image and are NOT free choices:
               # it reads JUPYTER_VNC_SESSIONS (default 3) and JUPYTER_VNC_PASSWORD (default
               # 'afon'). They were previously spelled VNC_SESSIONS and not set at all, so the
               # script silently used its own defaults - three VNC servers instead of one, and a
               # password that could not match the one Guacamole was provisioned with. guacd
               # reports the resulting rejection as "Unable to connect to VNC server", which reads
               # like the pod is unreachable; x11vnc's own log says "password check failed".
               c.KubeSpawner.environment = {
                   'JUPYTER_VNC_SESSIONS': '1',
                   'JUPYTER_VNC_PASSWORD': os.environ['JUPYTER_VNC_PASSWORD'],
               }
               # Stamp every spawned pod so the egress NetworkPolicy (NetworkConfig) selects it. The
               # per-user guacamole-user label is added on top of this by the auth_state hook below.
               c.KubeSpawner.extra_labels = {'%14$s': '%15$s'}

               # --- HDRS Patch 21: the four labels the audit platform's node agent reads. ---
               # The node agent runs on the Kubernetes node and never sees this pod's environment,
               # so the sidecar's AUDIT_* env vars are invisible to it. Tetragon puts pod_labels on
               # every exec event, which is how the identity of a workspace reaches a sensor outside
               # it - so these four are the whole route (svc-audit-log ADR 0031).
               #
               # A pod missing any of them produces NOTHING, by design and by test: the agent would
               # rather report nothing than report an event it cannot attribute. Written as literals
               # rather than built from a prefix so that grepping either repository for
               # `hdrs.audit/ae-id` finds both ends of the contract.
               #
               # Only non-empty values are stamped, so an afon deploying without the audit platform
               # is unchanged - the same property AUDIT_SIDECAR_IMAGE gives the sidecar.
               _audit_labels = {
                   'hdrs.audit/ae-id':      '%19$s',
                   'hdrs.audit/project-id': '%20$s',
                   'hdrs.audit/tre-id':     '%21$s',
               }
               c.KubeSpawner.extra_labels.update({k: v for k, v in _audit_labels.items() if v})

               # `hdrs.audit/user-id` is not here: it is per-user, so it is stamped in the
               # auth_state hook below, which is the only place the OIDC claims exist.
               #
               # Kubernetes label VALUES are at most 63 characters and must begin and end
               # alphanumeric, with only `-`, `_` and `.` between. A Keycloak `sub` is a UUID and
               # fits comfortably; another IdP's may not, and the honest failure is to refuse rather
               # than to mangle - a truncated or rewritten `sub` is a different identifier, and an
               # audit trail keyed on a silently altered one is worse than an absent label.
               # ASCII explicitly, and not str.isalnum(), which is Unicode-aware: 'é'.isalnum() is
               # True in Python and invalid in a Kubernetes label, so isalnum() would pass values
               # the API server then rejects - turning a bad label into a pod that will not spawn.
               _LABEL_ALNUM = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
               def _audit_label_value(value):
                   if not value or len(value) > 63:
                       return None
                   if value[0] not in _LABEL_ALNUM or value[-1] not in _LABEL_ALNUM:
                       return None
                   if not all(ch in _LABEL_ALNUM or ch in '-_.' for ch in value):
                       return None
                   return value

               # --- HDRS Patches 14-16: the audit platform's perimeter, beside the notebook. ---
               # svc-audit-log's docs/afon-integration.md. Step 2 reaches the sidecar via
               # HTTP(S)_PROXY in the workspace's environment, which the platform records as
               # tier 1 and forgeable - a researcher can unset an environment variable. Step 3
               # is the iptables REDIRECT that makes it tier 2, installed by an init container
               # holding NET_ADMIN; AUDIT_REDIRECT_IMAGE is the gate between the two.
               #
               # An empty AUDIT_SIDECAR_IMAGE removes all of it - afon must keep deploying
               # with no external auditor.
               #
               # The image refs below are baked at synth time and used only as FALLBACKS: the
               # live values are read from files under /etc/jupyterhub/perimeter-images at every
               # spawn (Patch 16), which the kubelet keeps in sync with the perimeter-images
               # ConfigMap. The audit platform's Deploy workflow patches that ConfigMap, so a new
               # sensor version reaches the next spawned workspace with no rebuild and no hub
               # restart. Ports and the step-3 gate stay baked - they are configuration of the
               # arrangement, not versions that roll.
               _AUDIT_IMAGE     = '%16$s'
               _AUDIT_REDIRECT  = '%22$s'
               _AUDIT_TPORT     = '%23$s'
               _AUDIT_EXCLUDED  = '%24$s'
               _AUDIT_CELLPORT  = '%25$s'
               # Where the cell sensor is copied to and mounted. Baked rather than
               # configured: it is a path two containers have to agree on, not a version.
               _AUDIT_CELLDIR   = '/opt/hdrs-audit'
               _AUDIT_CELLVOL   = 'hdrs-cell-sensor'
               _AUDIT_DIR       = '/etc/jupyterhub/perimeter-images'

               def _audit_ref(name, fallback):
                   try:
                       with open(_AUDIT_DIR + '/' + name) as f:
                           value = f.read().strip()
                           return value or fallback
                   except OSError:
                       return fallback

               def _audit_pre_spawn(spawner):
                   image = _audit_ref('AUDIT_SIDECAR_IMAGE', _AUDIT_IMAGE)
                   redirect = _audit_ref('AUDIT_REDIRECT_IMAGE', _AUDIT_REDIRECT)
                   if not image:
                       # No sidecar configured anywhere - afon must keep deploying with no
                       # external auditor, so this spawns an untouched workspace.
                       return
                   audit_env = [
                       {'name': 'KAFKA_BOOTSTRAP_SERVERS', 'value': '%17$s'},
                       {'name': 'SCHEMA_REGISTRY_URL', 'value': '%18$s'},
                       {'name': 'AE_ID', 'value': '%19$s'},
                       # One spawn of that environment - the stretch this perimeter can speak
                       # for. metadata.uid and NOT metadata.name: KubeSpawner derives the pod
                       # name from the username, so it is stable across spawns and would name
                       # the researcher rather than the spawn. The uid is fresh per pod, which
                       # is what a session is. Downward API rather than a synth-time value,
                       # because nothing outside Kubernetes knows it.
                       {'name': 'AE_SESSION_ID',
                        'valueFrom': {'fieldRef': {'fieldPath': 'metadata.uid'}}},
                       {'name': 'PROJECT_ID', 'value': '%20$s'},
                       {'name': 'TRE_ID', 'value': '%21$s'},
                       {'name': 'SENSOR_ID', 'value': 'perimeter'},
                   ]
                   if redirect:
                       # Step 3: TRANSPARENT_PORT turns on the mode that recovers the original
                       # destination from conntrack, because a redirect hands over an address
                       # and no name.
                       audit_env.append({'name': 'TRANSPARENT_PORT', 'value': _AUDIT_TPORT})
                   if _AUDIT_CELLPORT:
                       # HDRS Patch 25: the cell intake. A third way into the same sensor,
                       # beside the CONNECT proxy and the redirect - one container, one
                       # sensor id, one heartbeat (svc-audit-log ADR 0025, ADR 0031).
                       audit_env.append(
                           {'name': 'CELL_INTAKE_PORT', 'value': _AUDIT_CELLPORT})
                       # The actor, which only this event type needs: a NetworkFlow has no
                       # user_id and the sidecar has never carried one. It comes from the
                       # label Patch 21 already stamps, by the same Downward API route as
                       # AE_SESSION_ID above - metadata.labels['<KEY>'] is available to a
                       # container env fieldRef and not only to a downwardAPI volume, so
                       # there is no new config key and no second copy of the sub.
                       #
                       # Reaching it this way rather than from auth_state here is also what
                       # makes it independent of hook ORDER: the kubelet resolves the label
                       # when it creates the pod, by which time the auth_state hook has
                       # written it, whichever order the two hooks happen to run in.
                       #
                       # It is deliberately NOT taken from the posted cell. The extension
                       # runs in the researcher's own container, so anything it asserted
                       # about who ran a cell is something a researcher could assert about
                       # anyone. If the label is missing the sidecar refuses to publish and
                       # says so, rather than attributing a cell to nobody.
                       audit_env.append({
                           'name': 'USER_ID',
                           'valueFrom': {'fieldRef': {
                               'fieldPath': "metadata.labels['hdrs.audit/user-id']"}}})
                   spawner.extra_containers = [{
                       'name': 'workspace-perimeter',
                       'image': image,
                       'env': audit_env,
                   }]
                   cell_init = []
                   if _AUDIT_CELLPORT:
                       # The other half of Patch 25, and it goes in the NOTEBOOK container.
                       #
                       # The sensor is MOUNTED IN, not baked into the notebook image. That
                       # image is published by hand to a third party's registry, and
                       # image_pull_policy is IfNotPresent (a ~3 GB re-pull on every spawn
                       # evicted pods when a node ran out of ephemeral storage), so a
                       # rebuilt image under the same tag is not even picked up - every
                       # sensor change would need a tag bump, a Java change and somebody
                       # else's release. Copied out of the perimeter image instead, it
                       # rides AUDIT_SIDECAR_IMAGE through the perimeter-images ConfigMap
                       # and reaches the next spawned workspace like the sidecar does.
                       #
                       # A copy is enough because the package depends on nothing: it
                       # imports jupyter_server, which the notebook already has, and
                       # otherwise only the standard library. There is no index to reach
                       # from in here.
                       cell_init = [{
                           'name': 'hdrs-cell-sensor',
                           'image': image,
                           'command': ['python', 'src/install_cell_sensor.py',
                                       _AUDIT_CELLDIR],
                           'volumeMounts': [{'name': _AUDIT_CELLVOL,
                                             'mountPath': _AUDIT_CELLDIR}],
                           # Root purely to write into a fresh emptyDir, and nothing else:
                           # every capability is dropped. The perimeter image otherwise
                           # runs as uid 1337, which may or may not be able to write there
                           # depending on how the kubelet creates the volume - and the
                           # failure mode of guessing wrong is a workspace that will not
                           # spawn. The installer chmods what it writes to 0644/0755 so
                           # the notebook's own uid can read it.
                           'securityContext': {
                               'runAsUser': 0,
                               'capabilities': {'drop': ['ALL']},
                           },
                       }]
                       # The notebook reads the same volume, and needs both variables:
                       # PYTHONPATH so the module imports, JUPYTER_CONFIG_PATH so
                       # jpserver_extensions enables it. Neither is optional and neither
                       # alone does anything.
                       #
                       # Appended rather than set, even though both are unset in this image
                       # today: a future image that sets either would otherwise lose it
                       # silently, and PYTHONPATH in particular is the sort of thing a
                       # Spark image acquires.
                       spawner.volumes = (spawner.volumes or []) + [
                           {'name': _AUDIT_CELLVOL, 'emptyDir': {}}]
                       spawner.volume_mounts = (spawner.volume_mounts or []) + [
                           {'name': _AUDIT_CELLVOL, 'mountPath': _AUDIT_CELLDIR}]

                       cell_env = dict(spawner.environment or {})
                       def _prepend(name, value):
                           existing = cell_env.get(name)
                           cell_env[name] = value + ':' + existing if existing else value
                       _prepend('PYTHONPATH', _AUDIT_CELLDIR + '/lib')
                       _prepend('JUPYTER_CONFIG_PATH', _AUDIT_CELLDIR + '/etc/jupyter')
                       # Where the extension posts. The sidecar's AUDIT_* environment is
                       # invisible from this container, so it has to be told. 127.0.0.1
                       # because the two share the pod's network namespace.
                       #
                       # No default on the extension's side: unset, it loads, logs at ERROR
                       # and reports nothing, rather than guessing a port and going quiet.
                       cell_env['HDRS_AUDIT_SIDECAR_PORT'] = _AUDIT_CELLPORT
                       spawner.environment = cell_env
                   if redirect:
                       # The init container is the whole of what makes this tier 2, and the
                       # only thing in the pod holding NET_ADMIN. The sensor is excluded from
                       # its own rules BY UID (1337, pinned in the perimeter image).
                       #
                       # `cell_init +` rather than an assignment: the cell sensor's init
                       # container is set above and both have to survive. Ordering does not
                       # matter - one writes a volume, the other writes iptables rules, and
                       # neither reads the other's work.
                       spawner.init_containers = cell_init + [{
                           'name': 'perimeter-redirect',
                           'image': redirect,
                           'securityContext': {
                               'runAsUser': 0,
                               'capabilities': {'add': ['NET_ADMIN'], 'drop': ['ALL']},
                           },
                           'env': [
                               {'name': 'PERIMETER_UID', 'value': '1337'},
                               {'name': 'TRANSPARENT_PORT', 'value': _AUDIT_TPORT},
                               {'name': 'EXCLUDED_PORTS', 'value': _AUDIT_EXCLUDED},
                           ],
                       }]
                   else:
                       # Still needed here: without a redirect there is no other init
                       # container, and the cell sensor's would otherwise be dropped.
                       spawner.init_containers = cell_init
                       # Step 2 only: the CONNECT proxy, reached because the environment says
                       # so - tier 1 and forgeable, and recorded as such.
                       # NO_PROXY keeps the hub handshake and in-cluster services direct - and
                       # every entry on it is a route the perimeter does not see at this tier.
                       env = dict(spawner.environment or {})
                       env.update({
                           'HTTP_PROXY': 'http://127.0.0.1:3128',
                           'HTTPS_PROXY': 'http://127.0.0.1:3128',
                           'NO_PROXY': 'localhost,127.0.0.1,.svc,.cluster.local,%3$s',
                           'no_proxy': 'localhost,127.0.0.1,.svc,.cluster.local,%3$s',
                       })
                       spawner.environment = env
               c.KubeSpawner.pre_spawn_hook = _audit_pre_spawn

               # --- OIDC login against the same provider oauth2-proxy uses. JupyterHub is the OAuth
               #     client itself (not DummyAuthenticator) so it can capture each user's token via
               #     auth_state and hand it to their pod. Endpoints come from the issuer's discovery
               #     document, keeping this provider-agnostic. ---
               _issuer = '%9$s'.rstrip('/')
               _oidc = _discover(_issuer + '/.well-known/openid-configuration')
               c.JupyterHub.authenticator_class = 'generic-oauth'
               c.GenericOAuthenticator.client_id = os.environ['OAUTH_CLIENT_ID']
               c.GenericOAuthenticator.client_secret = ***REMOVED***'OAUTH_CLIENT_SECRET']
               c.GenericOAuthenticator.oauth_callback_url = 'https://%10$s%7$shub/oauth_callback'
               c.GenericOAuthenticator.authorize_url = _oidc['authorization_endpoint']
               c.GenericOAuthenticator.token_url = _oidc['token_endpoint']
               c.GenericOAuthenticator.userdata_url = _oidc['userinfo_endpoint']
               c.GenericOAuthenticator.scope = ['openid', 'email', 'profile']
               c.GenericOAuthenticator.username_claim = 'email'
               c.GenericOAuthenticator.enable_auth_state = True
               # Only the known roster may log in (these line up 1:1 with the Guacamole logins).
               c.GenericOAuthenticator.allow_all = False
               c.GenericOAuthenticator.allowed_users = {%11$s}
               
               # email -> DNS/label-safe slug, matching the per-user VNC Service names.
               _GUAC_SLUGS = {%12$s}
               
               async def _on_spawn(spawner, auth_state):
                   # Copied once, up front, and not inside the `if` below. c.KubeSpawner.extra_labels
                   # is a single dict shared by every spawner, so mutating it in place leaks one
                   # user's label into the next user's pod - the same hazard the environment copy
                   # further down exists for. Previously this copy only happened when the user had a
                   # slug, which left the audit label below writing into the shared dict.
                   spawner.extra_labels = dict(spawner.extra_labels or {})
                   # Label the pod with the user's slug so their guac-vnc-<slug> Service selects it.
                   slug = _GUAC_SLUGS.get(spawner.user.name)
                   if slug:
                       spawner.extra_labels['%8$s'] = slug
                   # HDRS Patch 21: the OIDC `sub`, which is what the audit platform means by
                   # user_id. It is the actor, and it is deliberately opaque - meaningless without
                   # the identity provider - which is what makes putting it in cluster metadata,
                   # readable by anyone with `get pods`, an acceptable disclosure. The e-mail on
                   # hub.jupyter.org/username is NOT a substitute: it identifies the person to
                   # anyone who reads it.
                   #
                   # Not spawner.user.name, even though username_claim is 'email' here and the two
                   # would agree: partners run afon against their own IdPs, and the audit trail
                   # must key on the claim the platform specified rather than on whatever afon
                   # happens to be using as a display name.
                   sub = _audit_label_value((auth_state or {}).get('oauth_user', {}).get('sub'))
                   if sub:
                       spawner.extra_labels['hdrs.audit/user-id'] = sub
                   else:
                       # Loudly, because the consequence is silence: the node agent drops every
                       # exec from a pod missing this label, so the workspace looks like one where
                       # nobody ran anything. C2 catches it eventually; this says it immediately.
                       spawner.log.warning(
                           'No usable OIDC sub for %%s - this workspace will produce no '
                           'user-input logs (svc-audit-log ADR 0031).', spawner.user.name)
                   # Inject the user's OIDC token, read by the Spark Connector and Polaris.
                   if auth_state:
                       # HDRS: copy before writing. c.KubeSpawner.environment is a single dict
                       # shared by every spawner, so mutating it in place leaves one user's
                       # token visible to the next user's pod. _audit_pre_spawn copies for the
                       # same reason.
                       env = dict(spawner.environment or {})
                       env['OIDC_TOKEN'] = auth_state.get('access_token', '')
                       if auth_state.get('id_token'):
                           env['OIDC_ID_TOKEN'] = auth_state['id_token']
                       spawner.environment = env
               c.Spawner.auth_state_hook = _on_spawn
               """.formatted(
            PROXY_API_SVC, PROXY_API,
            HUB_SVC, HUB_PORT,
            SINGLEUSER_IMAGE, SINGLEUSER_PORT,
            HUB_BASE_URL, GUACAMOLE_USER,
            issuer, jupyterDomain(),
            pythonSetOf(userProvider.getUsers().stream().map(AfonUser::email).toList()),
            pythonSlugMap(),
            URLEncoder.encode(GUACAMOLE_PATH, StandardCharsets.UTF_8),
            SINGLEUSER_LABEL_KEY, SINGLEUSER_LABEL_VALUE,
            properties.getValue(AfonProperty.AUDIT_SIDECAR_IMAGE, ""),
            properties.getValue(AfonProperty.AUDIT_KAFKA_BOOTSTRAP, ""),
            properties.getValue(AfonProperty.AUDIT_SCHEMA_REGISTRY_URL, ""),
            properties.getValue(AfonProperty.AUDIT_AE_ID, ""),
            properties.getValue(AfonProperty.AUDIT_PROJECT_ID, ""),
            // hdrs-afon, not hdrs: the default names this DEPLOYMENT of afon, and `hdrs`
            // alone names the Organization that runs it. Emrys runs another deployment from
            // the same framework, and an audit trail cannot be re-keyed once written.
            properties.getValue(AfonProperty.AUDIT_TRE_ID, "hdrs-afon"),
            properties.getValue(AfonProperty.AUDIT_REDIRECT_IMAGE, ""),
            properties.getValue(AfonProperty.AUDIT_TRANSPARENT_PORT, "15001"),
            // Hub API and Spark Connect. Each breaks the workspace if redirected - a
            // single-user server that cannot reach the hub never spawns - and each is
            // therefore a route the perimeter does not watch, named in the coverage
            // matrix rather than left implicit.
            properties.getValue(AfonProperty.AUDIT_EXCLUDED_PORTS, "8081 15002"),
            // Defaulted like the transparent port, and for the same reason the comment
            // above gives: a port two containers have to agree on is configuration of the
            // arrangement, not a version that rolls.
            //
            // It was empty, as a gate against a sidecar listening for cells no extension
            // would send. That gate is gone because the premise is: the sensor is copied
            // out of the sidecar's own image, so wherever there is a sidecar there is a
            // sensor. AUDIT_SIDECAR_IMAGE remains the real gate - unset, there is no
            // sidecar, no intake and no init container.
            properties.getValue(AfonProperty.AUDIT_CELL_INTAKE_PORT, "15003"));
    }

    /**
     * Renders a Python set literal body (no braces): {@code 'a', 'b'}.
     */
    private static String pythonSetOf(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Renders the email->slug dict literal body (no braces): {@code 'a@x': 'a', 'b@x': 'b'}.
     */
    private String pythonSlugMap() {
        return userProvider.getUsers().stream()
                         .map(u -> "'" + u.email() + "': '" + u.slug() + "'")
                         .reduce((a, b) -> a + ", " + b)
                         .orElse("");
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

    /**
     * Public domain JupyterHub is served on; used to build the OIDC callback URL.
     */
    protected abstract String jupyterDomain();

    /**
     * OIDC client id for JupyterHub's OAuthenticator (same provider as oauth2-proxy).
     */
    protected abstract String oauthClientId();

    /**
     * OIDC client secret for JupyterHub's OAuthenticator.
     */
    protected abstract String oauthClientSecret();
}