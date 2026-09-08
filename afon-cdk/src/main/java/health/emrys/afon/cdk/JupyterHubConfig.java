package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
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
        "ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi_pkg";

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
                "oauth.clientSecret", oauthClientSecret()))
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
            properties.getValue(AfonProperty.KEYCLOAK_DB_USERNAME),
            properties.getValue(AfonProperty.KEYCLOAK_DB_PASSWORD));
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
                envFromSecret("OAUTH_CLIENT_SECRET", SECRET_NAME, "oauth.clientSecret")))
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
                                                        .volume(List.of(configVolume))
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
               c.KubeSpawner.image_pull_policy = 'Always'
               c.KubeSpawner.port = %6$d
               c.KubeSpawner.start_timeout = 300
               c.KubeSpawner.http_timeout = 120
               # One in-pod VNC session per single-user server (see start-vnc.sh in the image).
               c.KubeSpawner.environment = {'VNC_SESSIONS': '1'}
               # Stamp every spawned pod so the egress NetworkPolicy (NetworkConfig) selects it. The
               # per-user guacamole-user label is added on top of this by the auth_state hook below.
               c.KubeSpawner.extra_labels = {'%14$s': '%15$s'}
               
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
                   # Label the pod with the user's slug so their guac-vnc-<slug> Service selects it.
                   slug = _GUAC_SLUGS.get(spawner.user.name)
                   if slug:
                       spawner.extra_labels = dict(spawner.extra_labels or {})
                       spawner.extra_labels['%8$s'] = slug
                   # Inject the user's OIDC token, read by the Spark Connector and Polaris.
                   if auth_state:
                       spawner.environment['OIDC_TOKEN'] = auth_state.get('access_token', '')
                       if auth_state.get('id_token'):
                           spawner.environment['OIDC_ID_TOKEN'] = auth_state['id_token']
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
            SINGLEUSER_LABEL_KEY, SINGLEUSER_LABEL_VALUE);
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