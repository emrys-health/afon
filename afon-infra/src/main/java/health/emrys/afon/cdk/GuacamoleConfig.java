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
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerPort;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeEmptyDir;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.cdk.TFUtils.clusterIpService;
import static health.emrys.afon.cdk.DeploymentUtils.withAppKubernetesIoName;
import static health.emrys.afon.cdk.TFUtils.envVar;

/**
 * Deploys Apache Guacamole as a clientless remote desktop gateway so that the GUI of the
 * Jupyter pod (rendered into an in-pod X server such as Xvfb and exposed via VNC) can be
 * streamed to a regular web browser.
 *
 * <p>Two containers run side by side in the same Pod:
 * <ul>
 *   <li>{@code guacd} — the protocol proxy daemon (port 4822) that speaks VNC/RDP/SSH.</li>
 *   <li>{@code guacamole} — the Tomcat web app (port 8080) that the browser connects to.</li>
 * </ul>
 *
 * <p>A ConfigMap supplies a static {@code user-mapping.xml} which preconfigures a single
 * connection pointing at the Jupyter pod's VNC server. This avoids having to stand up a
 * MySQL/Postgres backend just to host one connection.
 *
 * <p>Note: this only deploys Guacamole and a ClusterIP service. Exposing it to the public
 * internet (Ingress + ACM cert + DNS) is left as a follow-up so it can be put behind the
 * existing oauth2-proxy MFA gate.
 */
public class GuacamoleConfig {

    static final int    WEB_PORT        = 8080;
    static final int    GUACD_PORT      = 4822;
    static final String SVC_NAME        = "guacamole-web";
    static final String CONFIG_MAP_NAME = "guacamole-config";

    private static final Map<String, String> LABELS = Map.of("app", "guacamole");

    /** One Guacamole login → one in-pod VNC port → one independent X session. */
    public record Session(String username, String password, int vncPort) {}

    private static final String AUTHORIZE_BLOCK_TEMPLATE = """
                <authorize username="%s" password="%s">
                    <connection name="jupyter-vnc-%s">
                        <protocol>vnc</protocol>
                        <param name="hostname">%s</param>
                        <param name="port">%d</param>
                        <param name="password">%s</param>
                        <param name="disable-copy">true</param>
                        <param name="disable-paste">true</param>
                    </connection>
                </authorize>
            """;

    private static final String GUACAMOLE_PROPERTIES = """
            guacd-hostname: localhost
            guacd-port: 4822
            """;

    GuacamoleConfig(Construct scope,
                    List<ITerraformDependable> dependsOn,
                    String jupyterVncHost,
                    String jupyterVncPassword,
                    List<Session> sessions) {
        // All sessions live on the same (single) Jupyter pod, just on different VNC ports.
        this(scope, dependsOn, session -> jupyterVncHost, jupyterVncPassword, sessions);
    }

    /**
     * Variant where each session can target a <em>different</em> VNC host — used by the JupyterHub
     * deployment, where every user has their own pod (and thus its own per-user VNC Service) rather
     * than sharing one pod across ports. Classic file login (username + VNC password).
     */
    GuacamoleConfig(Construct scope,
                    List<ITerraformDependable> dependsOn,
                    Function<Session, String> hostResolver,
                    String jupyterVncPassword,
                    List<Session> sessions) {
        this(scope, dependsOn, hostResolver, jupyterVncPassword, sessions, null);
    }

    /**
     * Canonical constructor.
     *
     * <p>When {@code ssoUsernameHeader} is non-null, Guacamole runs in transparent SSO mode: the
     * {@code guacamole-auth-header} extension authenticates each request from that HTTP header
     * (set by the upstream oauth2-proxy, e.g. {@code X-Forwarded-Email}), so there is no separate
     * Guacamole login and no shared password — the user lands straight on the connection whose
     * {@code <authorize>} username matches the header value. When null, the classic file login
     * (username + password from {@code user-mapping.xml}) is used.
     *
     * <p>The header extension is a jar that must live in {@code $GUACAMOLE_HOME/extensions}, so the
     * home directory is assembled at start-up by an initContainer (config from the ConfigMap + the
     * extension jar bundled in the image) into a writable emptyDir, rather than mounting the
     * ConfigMap read-only as the home.
     */
    GuacamoleConfig(Construct scope,
                    List<ITerraformDependable> dependsOn,
                    Function<Session, String> hostResolver,
                    String jupyterVncPassword,
                    List<Session> sessions,
                    String ssoUsernameHeader) {

        String guacamoleProperties = GUACAMOLE_PROPERTIES;
        if (ssoUsernameHeader != null) {
            guacamoleProperties += "http-auth-header: " + ssoUsernameHeader + "\n";
        }

        ConfigMap configMap = ConfigMap.Builder
                .create(scope, "guacamoleConfigMap")
                .metadata(ConfigMapMetadata.builder()
                                           .name(CONFIG_MAP_NAME)
                                           .namespace("default")
                                           .build())
                .data(Map.of(
                        "user-mapping.xml", buildUserMappingXml(
                                hostResolver,
                                jupyterVncPassword,
                                sessions),
                        "guacamole.properties", guacamoleProperties))
                .build();

        // Assemble GUACAMOLE_HOME (a writable emptyDir) from the ConfigMap plus, in SSO mode, the
        // header-auth extension jar bundled in the guacamole image — a read-only ConfigMap mount
        // can't host the extensions/ directory.
        StringBuilder assemble = new StringBuilder()
                .append("set -e; mkdir -p /etc/guacamole/extensions; ")
                .append("cp /tmp/guacamole-config/* /etc/guacamole/; ");
        if (ssoUsernameHeader != null) {
            assemble.append("cp \"$(find /opt/guacamole -name 'guacamole-auth-header-*.jar' | head -1)\" ")
                    .append("/etc/guacamole/extensions/; ");
        }

        DeploymentSpecTemplateSpecInitContainer initContainer = DeploymentSpecTemplateSpecInitContainer
                .builder()
                .name("guacamole-home-init")
                .image("guacamole/guacamole:1.5.5")
                .command(List.of("sh", "-c", assemble.toString()))
                .volumeMount(List.of(
                        DeploymentSpecTemplateSpecInitContainerVolumeMount.builder()
                                .name("guacamole-config").mountPath("/tmp/guacamole-config").readOnly(true).build(),
                        DeploymentSpecTemplateSpecInitContainerVolumeMount.builder()
                                .name("guacamole-home").mountPath("/etc/guacamole").build()))
                .build();

        DeploymentSpecTemplateSpecContainer guacdContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("guacd")
                .image("guacamole/guacd:1.5.5")
                .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                     .containerPort(GUACD_PORT)
                                                                     .protocol("TCP")
                                                                     .build()))
                .build();

        DeploymentSpecTemplateSpecContainer guacamoleContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("guacamole")
                .image("guacamole/guacamole:1.5.5")
                .env(List.of(
                        envVar("GUACD_HOSTNAME", "localhost"),
                        envVar("GUACD_PORT", String.valueOf(GUACD_PORT)),
                        envVar("GUACAMOLE_HOME", "/etc/guacamole")))
                .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                     .containerPort(WEB_PORT)
                                                                     .protocol("TCP")
                                                                     .build()))
                .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                              .name("guacamole-home")
                                                              .mountPath("/etc/guacamole")
                                                              .build()))
                .build();

        DeploymentSpecTemplateSpecVolume configVolume = DeploymentSpecTemplateSpecVolume
                .builder()
                .name("guacamole-config")
                .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                    .name(CONFIG_MAP_NAME)
                                                                    .build())
                .build();
        DeploymentSpecTemplateSpecVolume homeVolume = DeploymentSpecTemplateSpecVolume
                .builder()
                .name("guacamole-home")
                .emptyDir(DeploymentSpecTemplateSpecVolumeEmptyDir.builder().build())
                .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(configMap);

        Deployment.Builder.create(scope, "guacamoleDeployment")
                .dependsOn(deps)
                .metadata(DeploymentMetadata.builder()
                                            .name("guacamole-deployment")
                                            .labels(LABELS)
                                            .build())
                .spec(DeploymentSpec.builder()
                                    .replicas("1")
                                    .selector(DeploymentSpecSelector.builder().matchLabels(LABELS).build())
                                    .template(DeploymentSpecTemplate.builder()
                                            .metadata(DeploymentSpecTemplateMetadata.builder()
                                                              .labels(withAppKubernetesIoName(LABELS))
                                                              .build())
                                            .spec(DeploymentSpecTemplateSpec.builder()
                                                          .initContainer(List.of(initContainer))
                                                          .container(List.of(guacdContainer, guacamoleContainer))
                                                          .volume(List.of(configVolume, homeVolume))
                                                          .build())
                                            .build())
                                    .build())
                .build();

        List<ServiceSpecPort> ports = List.of(ServiceSpecPort.builder()
                                                             .name("http")
                                                             .port(WEB_PORT)
                                                             .targetPort(String.valueOf(WEB_PORT))
                                                             .protocol("TCP")
                                                             .build());
        clusterIpService(deps, LABELS, SVC_NAME, ports, scope);
    }

    private static String buildUserMappingXml(java.util.function.Function<Session, String> hostResolver,
                                              String vncPassword,
                                              List<Session> sessions) {
        StringBuilder sb = new StringBuilder("<user-mapping>\n");
        for (Session s : sessions) {
            sb.append(String.format(
                    AUTHORIZE_BLOCK_TEMPLATE,
                    s.username(), s.password(),
                    s.username(),
                    hostResolver.apply(s), s.vncPort(), vncPassword));
        }
        sb.append("</user-mapping>\n");
        return sb.toString();
    }

}