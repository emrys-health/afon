package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerPort;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
class GuacamoleConfig {

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

        ConfigMap configMap = ConfigMap.Builder
                .create(scope, "guacamoleConfigMap")
                .metadata(ConfigMapMetadata.builder()
                                           .name(CONFIG_MAP_NAME)
                                           .namespace("default")
                                           .build())
                .data(Map.of(
                        "user-mapping.xml", buildUserMappingXml(
                                jupyterVncHost,
                                jupyterVncPassword,
                                sessions),
                        "guacamole.properties", GUACAMOLE_PROPERTIES))
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

        DeploymentSpecTemplateSpecContainerVolumeMount guacamoleHomeMount =
                DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                              .name("guacamole-home")
                                                              .mountPath("/etc/guacamole")
                                                              .readOnly(true)
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
                .volumeMount(List.of(guacamoleHomeMount))
                .build();

        DeploymentSpecTemplateSpecVolume guacamoleHomeVolume = DeploymentSpecTemplateSpecVolume
                .builder()
                .name("guacamole-home")
                .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                    .name(CONFIG_MAP_NAME)
                                                                    .build())
                .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(configMap);

        DeploymentUtils.deploy(
                scope,
                deps,
                LABELS,
                List.of(guacdContainer, guacamoleContainer),
                List.of(guacamoleHomeVolume),
                "guacamole");

        Service.Builder.create(scope, SVC_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(SVC_NAME)
                                                .namespace("default")
                                                .build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(LABELS)
                                        .type("ClusterIP")
                                        .port(List.of(ServiceSpecPort.builder()
                                                                     .name("http")
                                                                     .port(WEB_PORT)
                                                                     .targetPort(String.valueOf(WEB_PORT))
                                                                     .protocol("TCP")
                                                                     .build()))
                                        .build())
                       .build();
    }

    private static String buildUserMappingXml(String host, String vncPassword, List<Session> sessions) {
        StringBuilder sb = new StringBuilder("<user-mapping>\n");
        for (Session s : sessions) {
            sb.append(String.format(
                    AUTHORIZE_BLOCK_TEMPLATE,
                    s.username(), s.password(),
                    s.username(),
                    host, s.vncPort(), vncPassword));
        }
        sb.append("</user-mapping>\n");
        return sb.toString();
    }

    private static DeploymentSpecTemplateSpecContainerEnv envVar(String name, String value) {
        return DeploymentSpecTemplateSpecContainerEnv.builder()
                                                     .name(name)
                                                     .value(value)
                                                     .build();
    }
}