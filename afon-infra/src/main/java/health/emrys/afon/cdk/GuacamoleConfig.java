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
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1Spec;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplate;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateMetadata;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpec;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecInitContainer;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecInitContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecVolumeEmptyDir;
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

    /** Connection details for the shared Postgres that backs Guacamole in transparent-SSO mode. */
    public record DbConfig(String host, int port, String database, String user, String password) {}

    private static final String PROVISION_CM = "guacamole-db-provision";
    private static final String DB_INIT_JOB  = "guacamole-db-init";

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
                        // Deploy the webapp at the root context ("/") instead of the default
                        // "/guacamole/". Guacamole owns the domain root behind oauth2-proxy (which maps
                        // "/" here); without this, "/" returns 404 and only "/guacamole/" works.
                        envVar("WEBAPP_CONTEXT", "ROOT"),
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

    /**
     * Database-backed, transparent-SSO deployment (no Guacamole login page).
     *
     * <p>The {@code guacamole-auth-header} extension authenticates each request from
     * {@code httpAuthHeader} (set by the upstream oauth2-proxy, e.g. {@code X-Forwarded-Email}), and
     * {@code guacamole-auth-jdbc} serves each user their own VNC connection from Postgres — the file
     * provider's "Session not associated with authentication provider default" limitation is why a DB
     * is required for SSO. A one-shot Job creates the database + schema and idempotently provisions one
     * connection per session (granted to the matching user). The Guacamole image auto-installs the JDBC
     * and header extensions from the {@code POSTGRESQL_*} / {@code HEADER_ENABLED} env vars.
     */
    GuacamoleConfig(Construct scope,
                    List<ITerraformDependable> dependsOn,
                    Function<Session, String> hostResolver,
                    String vncPassword,
                    List<Session> sessions,
                    DbConfig db,
                    String httpAuthHeader) {

        ConfigMap provisionCm = ConfigMap.Builder
                .create(scope, "guacamoleProvisionCm")
                .metadata(ConfigMapMetadata.builder()
                                           .name(PROVISION_CM)
                                           .namespace("default")
                                           .build())
                .data(Map.of("provision.sql", buildProvisioningSql(hostResolver, vncPassword, sessions)))
                .build();

        List<ITerraformDependable> jobDeps = new ArrayList<>(dependsOn);
        jobDeps.add(provisionCm);
        JobV1 dbInit = buildDbInitJob(scope, jobDeps, db);

        DeploymentSpecTemplateSpecContainer guacdContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("guacd")
                .image("guacamole/guacd:1.5.5")
                .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                     .containerPort(GUACD_PORT)
                                                                     .protocol("TCP")
                                                                     .build()))
                .build();

        // The image self-configures: setting POSTGRESQL_* installs guacamole-auth-jdbc (+ the bundled
        // JDBC driver) and writes guacamole.properties; HEADER_ENABLED installs guacamole-auth-header.
        // No ConfigMap/user-mapping.xml — there is no login page in this mode.
        DeploymentSpecTemplateSpecContainer guacamoleContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("guacamole")
                .image("guacamole/guacamole:1.5.5")
                .env(List.of(
                        envVar("GUACD_HOSTNAME", "localhost"),
                        envVar("GUACD_PORT", String.valueOf(GUACD_PORT)),
                        envVar("WEBAPP_CONTEXT", "ROOT"),
                        envVar("POSTGRESQL_HOSTNAME", db.host()),
                        envVar("POSTGRESQL_PORT", String.valueOf(db.port())),
                        envVar("POSTGRESQL_DATABASE", db.database()),
                        envVar("POSTGRESQL_USER", db.user()),
                        envVar("POSTGRESQL_PASSWORD", db.password()),
                        // We pre-provision every user, so don't auto-create (permission-less) accounts.
                        envVar("POSTGRESQL_AUTO_CREATE_ACCOUNTS", "false"),
                        envVar("HEADER_ENABLED", "true"),
                        envVar("HTTP_AUTH_HEADER", httpAuthHeader)))
                .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                                                                     .containerPort(WEB_PORT)
                                                                     .protocol("TCP")
                                                                     .build()))
                .build();

        List<ITerraformDependable> deployDeps = new ArrayList<>(dependsOn);
        deployDeps.add(dbInit);

        Deployment.Builder.create(scope, "guacamoleDeployment")
                .dependsOn(deployDeps)
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
                                                          .container(List.of(guacdContainer, guacamoleContainer))
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
        clusterIpService(deployDeps, LABELS, SVC_NAME, ports, scope);
    }

    /**
     * One-shot Job that prepares the Guacamole database: an initContainer generates the Postgres
     * schema from the Guacamole image, then a psql container creates the database (if absent), applies
     * the schema (if absent) and runs the (idempotent) connection-provisioning SQL from the ConfigMap.
     */
    private static JobV1 buildDbInitJob(Construct scope,
                                        List<ITerraformDependable> dependsOn,
                                        DbConfig db) {
        JobV1SpecTemplateSpecInitContainer schemaGen = JobV1SpecTemplateSpecInitContainer
                .builder()
                .name("schema-gen")
                .image("guacamole/guacamole:1.5.5")
                .command(List.of("sh", "-c", "/opt/guacamole/bin/initdb.sh --postgresql > /sql/001-schema.sql"))
                .volumeMount(List.of(JobV1SpecTemplateSpecInitContainerVolumeMount.builder()
                                                                                  .name("sql")
                                                                                  .mountPath("/sql")
                                                                                  .build()))
                .build();

        String script = String.join("\n",
            "set -e",
            "until pg_isready -h \"$PGHOST\" -p \"$PGPORT\" -U \"$PGUSER\"; do echo 'waiting for postgres'; sleep 2; done",
            "psql -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='$GUAC_DB'\" | grep -q 1 \\",
            "  || psql -d postgres -c \"CREATE DATABASE \\\"$GUAC_DB\\\"\"",
            "if [ \"$(psql -d \"$GUAC_DB\" -tAc \"SELECT to_regclass('public.guacamole_connection')\")\" != 'guacamole_connection' ]; then",
            "  psql -d \"$GUAC_DB\" -v ON_ERROR_STOP=1 -f /sql/001-schema.sql",
            "fi",
            "psql -d \"$GUAC_DB\" -v ON_ERROR_STOP=1 -f /provision/provision.sql",
            "echo 'guacamole db init complete'");

        JobV1SpecTemplateSpecContainer dbInit = JobV1SpecTemplateSpecContainer
                .builder()
                .name("db-init")
                .image("postgres:16-alpine")
                .command(List.of("sh", "-c", script))
                .env(List.of(
                        jobEnv("PGHOST", db.host()),
                        jobEnv("PGPORT", String.valueOf(db.port())),
                        jobEnv("PGUSER", db.user()),
                        jobEnv("PGPASSWORD", db.password()),
                        jobEnv("GUAC_DB", db.database())))
                .volumeMount(List.of(
                        JobV1SpecTemplateSpecContainerVolumeMount.builder()
                                .name("sql").mountPath("/sql").build(),
                        JobV1SpecTemplateSpecContainerVolumeMount.builder()
                                .name("provision").mountPath("/provision").readOnly(true).build()))
                .build();

        return JobV1.Builder.create(scope, "guacamoleDbInitJob")
                .dependsOn(dependsOn)
                .metadata(JobV1Metadata.builder().name(DB_INIT_JOB).namespace("default").build())
                .spec(JobV1Spec.builder()
                              .backoffLimit(6)
                              .template(JobV1SpecTemplate.builder()
                                    .metadata(JobV1SpecTemplateMetadata.builder()
                                                      .labels(Map.of("app", DB_INIT_JOB))
                                                      .build())
                                    .spec(JobV1SpecTemplateSpec.builder()
                                                  .restartPolicy("OnFailure")
                                                  .initContainer(List.of(schemaGen))
                                                  .container(List.of(dbInit))
                                                  .volume(List.of(
                                                          JobV1SpecTemplateSpecVolume.builder()
                                                                  .name("sql")
                                                                  .emptyDir(JobV1SpecTemplateSpecVolumeEmptyDir.builder().build())
                                                                  .build(),
                                                          JobV1SpecTemplateSpecVolume.builder()
                                                                  .name("provision")
                                                                  .configMap(JobV1SpecTemplateSpecVolumeConfigMap.builder()
                                                                                     .name(PROVISION_CM)
                                                                                     .build())
                                                                  .build()))
                                                  .build())
                                    .build())
                              .build())
                .build();
    }

    private static JobV1SpecTemplateSpecContainerEnv jobEnv(String name, String value) {
        return JobV1SpecTemplateSpecContainerEnv.builder().name(name).value(value).build();
    }

    /**
     * Idempotent SQL that provisions, per session, a VNC connection targeting that user's pod and
     * grants it to the user (username = the SSO header value, i.e. their email). Managed connections
     * are dropped by name first, so re-running (a redeploy) refreshes host/password cleanly; user rows
     * persist via ON CONFLICT. Delivered via a ConfigMap file, so no shell escaping is involved.
     */
    private static String buildProvisioningSql(Function<Session, String> hostResolver,
                                               String vncPassword,
                                               List<Session> sessions) {
        StringBuilder sb = new StringBuilder();
        for (Session s : sessions) {
            String user = s.username();
            String conn = "vnc-" + user;
            String host = hostResolver.apply(s);
            sb.append("BEGIN;\n")
              .append("DELETE FROM guacamole_connection WHERE connection_name = '").append(conn).append("';\n")
              .append("DO $do$\n")
              .append("DECLARE eid integer; cid integer;\n")
              .append("BEGIN\n")
              .append("  INSERT INTO guacamole_entity (name, type) VALUES ('").append(user).append("', 'USER') ON CONFLICT (type, name) DO NOTHING;\n")
              .append("  SELECT entity_id INTO eid FROM guacamole_entity WHERE name = '").append(user).append("' AND type = 'USER';\n")
              .append("  INSERT INTO guacamole_user (entity_id, password_hash, password_date) VALUES (eid, decode('00','hex'), now()) ON CONFLICT (entity_id) DO NOTHING;\n")
              .append("  INSERT INTO guacamole_connection (connection_name, protocol) VALUES ('").append(conn).append("', 'vnc') RETURNING connection_id INTO cid;\n")
              .append("  INSERT INTO guacamole_connection_parameter (connection_id, parameter_name, parameter_value) VALUES\n")
              .append("    (cid, 'hostname', '").append(host).append("'),\n")
              .append("    (cid, 'port', '").append(s.vncPort()).append("'),\n")
              .append("    (cid, 'password', '").append(vncPassword).append("'),\n")
              .append("    (cid, 'disable-copy', 'true'),\n")
              .append("    (cid, 'disable-paste', 'true');\n")
              .append("  INSERT INTO guacamole_connection_permission (entity_id, connection_id, permission) VALUES (eid, cid, 'READ') ON CONFLICT DO NOTHING;\n")
              .append("END $do$;\n")
              .append("COMMIT;\n");
        }
        return sb.toString();
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