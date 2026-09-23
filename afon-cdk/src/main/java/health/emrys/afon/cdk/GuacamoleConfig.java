package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.*;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import software.constructs.Construct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static health.emrys.afon.cdk.CdkHelper.clusterIpService;
import static health.emrys.afon.cdk.CdkHelper.envVar;
import static health.emrys.afon.cdk.CdkUtils.withAppKubernetesIoName;

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
    static final String GUACAMOLE_IMAGE = "guacamole/guacamole:1.5.5";
    /**
     * The active theme's branding extension jar (built from the afon-theme module's
     * {@code themes/<theme>/guacamole/} resources on the synth classpath — see {@link #themeJarBytes}).
     * Shipped to the pod as a ConfigMap binaryData entry, then copied into GUACAMOLE_HOME/extensions
     * by the guacamole-home-init initContainer in both deployment modes.
     */
    private static final String THEME_CONFIG_MAP = "guacamole-theme";
    private static final String THEME_JAR_NAME   = "guacamole-theme.jar";
    private static final String THEME_VOLUME     = "guacamole-theme";
    private static final String THEME_MOUNT      = "/tmp/guacamole-theme";
    private static final String HOME_VOLUME      = "guacamole-home";
    private static final String HOME_MOUNT       = "/etc/guacamole";

    private static final Map<String, String> LABELS = Map.of("app", "guacamole");

    /** One Guacamole login → one in-pod VNC port → one independent X session. */
    public record Session(String username, String password, int vncPort) {}

    /** Connection details for the shared Postgres that backs Guacamole in transparent-SSO mode. */
    public record DbConfig(String host, int port, String database, String user, String password) {

        public static DbConfig create(AfonPropertyProvider properties) {
            return new DbConfig(properties.getValue(AfonProperty.GUACAMOLE_DB_HOST),
                                5432,
                                "guacamole-" + properties.getValue(AfonProperty.CLUSTER_NAME),
                                properties.getValue(AfonProperty.GUACAMOLE_DB_USERNAME),
                                properties.getValue(AfonProperty.GUACAMOLE_DB_PASSWORD));
        }

    }

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
                    List<Session> sessions,
                    String theme) {
        // All sessions live on the same (single) Jupyter pod, just on different VNC ports.
        this(scope, dependsOn, session -> jupyterVncHost, jupyterVncPassword, sessions, theme);
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
                    List<Session> sessions,
                    String theme) {
        this(scope, dependsOn, hostResolver, jupyterVncPassword, sessions, null, theme);
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
                    String ssoUsernameHeader,
                    String theme) {

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

        ConfigMap themeCm = buildThemeConfigMap(scope, theme);

        // Assemble GUACAMOLE_HOME (a writable emptyDir) from the ConfigMap plus, in SSO mode, the
        // header-auth extension jar bundled in the guacamole image — a read-only ConfigMap mount
        // can't host the extensions/ directory. The branding extension jar arrives the same way,
        // from its own ConfigMap.
        StringBuilder assemble = new StringBuilder()
                .append("set -e; mkdir -p /etc/guacamole/extensions; ")
                .append("cp /tmp/guacamole-config/* /etc/guacamole/; ")
                .append("cp " + THEME_MOUNT + "/*.jar /etc/guacamole/extensions/; ");
        if (ssoUsernameHeader != null) {
            assemble.append("cp \"$(find /opt/guacamole -name 'guacamole-auth-header-*.jar' | head -1)\" ")
                    .append("/etc/guacamole/extensions/; ");
        }

        DeploymentSpecTemplateSpecInitContainer initContainer = DeploymentSpecTemplateSpecInitContainer
                .builder()
                .name("guacamole-home-init")
                .image(GUACAMOLE_IMAGE)
                .command(List.of("sh", "-c", assemble.toString()))
                .volumeMount(List.of(
                        initVolumeMount("guacamole-config", "/tmp/guacamole-config", true),
                        initVolumeMount(THEME_VOLUME, THEME_MOUNT, true),
                        initVolumeMount(HOME_VOLUME, HOME_MOUNT, false)))
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
                .image(GUACAMOLE_IMAGE)
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
                                                              .name(HOME_VOLUME)
                                                              .mountPath(HOME_MOUNT)
                                                              .build()))
                .build();

        DeploymentSpecTemplateSpecVolume configVolume = DeploymentSpecTemplateSpecVolume
                .builder()
                .name("guacamole-config")
                .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                    .name(CONFIG_MAP_NAME)
                                                                    .build())
                .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(configMap);
        deps.add(themeCm);

        Deployment.Builder.create(scope, "guacamoleDeployment")
                .dependsOn(deps)
                // See CdkUtils.deploy: don't burn the 15-minute EKS token window waiting for
                // pod readiness on an app workload nothing downstream depends on. Trade-off: a broken
                // rollout won't fail the apply — surface it via monitoring/kubectl.
                .waitForRollout(false)
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
                                                          .volume(List.of(configVolume, homeVolume(), themeVolume()))
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
                    String httpAuthHeader,
                    String theme) {

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

        ConfigMap themeCm = buildThemeConfigMap(scope, theme);

        // GUACAMOLE_HOME is a writable emptyDir at /etc/guacamole, populated by an initContainer
        // with the branding extension jar from the theme ConfigMap. The image treats that dir as a
        // template, cp -a'ing it into ~/.guacamole at boot (guacamole-docker's start.sh), so the
        // theme jar ends up alongside the JDBC/header jars the env vars below auto-install.
        DeploymentSpecTemplateSpecInitContainer initContainer = DeploymentSpecTemplateSpecInitContainer
                .builder()
                .name("guacamole-home-init")
                .image(GUACAMOLE_IMAGE)
                .command(List.of("sh", "-c",
                        "set -e; mkdir -p /etc/guacamole/extensions; "
                        + "cp " + THEME_MOUNT + "/*.jar /etc/guacamole/extensions/;"))
                .volumeMount(List.of(
                        initVolumeMount(THEME_VOLUME, THEME_MOUNT, true),
                        initVolumeMount(HOME_VOLUME, HOME_MOUNT, false)))
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

        // The image self-configures: setting POSTGRESQL_* installs guacamole-auth-jdbc (+ the bundled
        // JDBC driver) and writes guacamole.properties; HEADER_ENABLED installs guacamole-auth-header.
        // No ConfigMap/user-mapping.xml — there is no login page in this mode.
        DeploymentSpecTemplateSpecContainer guacamoleContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("guacamole")
                .image(GUACAMOLE_IMAGE)
                .env(List.of(
                        envVar("GUACD_HOSTNAME", "localhost"),
                        envVar("GUACD_PORT", String.valueOf(GUACD_PORT)),
                        // The emptyDir the initContainer filled with the branding jar — copied
                        // into ~/.guacamole at boot (see the initContainer above).
                        envVar("GUACAMOLE_HOME", "/etc/guacamole"),
                        // Served at the default /guacamole/ context (not ROOT): JupyterHub now owns the
                        // domain root so that a bare visit auto-spawns the user's pod, then hands off to
                        // Guacamole under /guacamole/ to stream that pod's desktop.
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
                .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                              .name(HOME_VOLUME)
                                                              .mountPath(HOME_MOUNT)
                                                              .build()))
                .build();

        List<ITerraformDependable> deployDeps = new ArrayList<>(dependsOn);
        deployDeps.add(dbInit);
        deployDeps.add(themeCm);

        Deployment.Builder.create(scope, "guacamoleDeployment")
                .dependsOn(deployDeps)
                // See CdkUtils.deploy: don't burn the 15-minute EKS token window waiting for
                // pod readiness on an app workload nothing downstream depends on. Trade-off: a broken
                // rollout won't fail the apply — surface it via monitoring/kubectl.
                .waitForRollout(false)
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
                                                          .volume(List.of(homeVolume(), themeVolume()))
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

        String script = """
                        set -e
                        until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"; do echo 'waiting for postgres'; sleep 2; done
                        psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$GUAC_DB'" | grep -q 1 \\
                          || psql -d postgres -c "CREATE DATABASE \\"$GUAC_DB\\""
                        if [ "$(psql -d "$GUAC_DB" -tAc "SELECT to_regclass('public.guacamole_connection')")" != 'guacamole_connection' ]; then
                          psql -d "$GUAC_DB" -v ON_ERROR_STOP=1 -f /sql/001-schema.sql
                        fi
                        psql -d "$GUAC_DB" -v ON_ERROR_STOP=1 -f /provision/provision.sql
                        echo 'guacamole db init complete'
                        """;

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
                // The provider waits only 1 minute for completion by default, most of which is spent
                // pulling the two images before any SQL runs. The work itself takes about a second.
                .timeouts(JobV1Timeouts.builder().create("10m").build())
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
        sb.append("""
                  BEGIN;
                  DELETE FROM guacamole_entity WHERE name = 'guacadmin' AND type = 'USER';
                  COMMIT;
                  """);
        for (Session s : sessions) {
            String user = s.username();
            String conn = "vnc-" + user;
            String host = hostResolver.apply(s);
            sb.append("BEGIN;\n")
              .append("DELETE FROM guacamole_connection WHERE connection_name = '").append(conn).append("';\n")
//              .append("DELETE FROM guacamole_entity WHERE name = 'guacadmin' AND type = 'USER';\n")
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

    /**
     * The active theme's Guacamole extension jar as a ConfigMap {@code binaryData} entry — the jar
     * bytes come from the afon-theme artifact on the synth classpath (see {@link #themeJarBytes}).
     */
    private static ConfigMap buildThemeConfigMap(Construct scope, String theme) {
        return ConfigMap.Builder
                .create(scope, "guacamoleThemeConfigMap")
                .metadata(ConfigMapMetadata.builder()
                                           .name(THEME_CONFIG_MAP)
                                           .namespace("default")
                                           .build())
                .binaryData(Map.of(THEME_JAR_NAME,
                                   Base64.getEncoder().encodeToString(themeJarBytes(theme))))
                .build();
    }

    private static DeploymentSpecTemplateSpecVolume themeVolume() {
        return DeploymentSpecTemplateSpecVolume.builder()
                .name(THEME_VOLUME)
                .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder()
                                                                    .name(THEME_CONFIG_MAP)
                                                                    .build())
                .build();
    }

    private static DeploymentSpecTemplateSpecVolume homeVolume() {
        return DeploymentSpecTemplateSpecVolume.builder()
                .name(HOME_VOLUME)
                .emptyDir(DeploymentSpecTemplateSpecVolumeEmptyDir.builder().build())
                .build();
    }

    private static DeploymentSpecTemplateSpecInitContainerVolumeMount initVolumeMount(String name,
                                                                                      String path,
                                                                                      boolean readOnly) {
        return DeploymentSpecTemplateSpecInitContainerVolumeMount.builder()
                                                                 .name(name)
                                                                 .mountPath(path)
                                                                 .readOnly(readOnly)
                                                                 .build();
    }

    /**
     * Bytes of {@code theme}'s Guacamole extension, packaged as a standalone jar from whatever files
     * {@link ThemeResources} finds under {@code themes/<theme>/guacamole/} — so any theme's own file
     * set becomes the extension, with {@code guac-manifest.json} landing at the jar's root as
     * Guacamole requires.
     */
    private static byte[] themeJarBytes(String theme) {
        Map<String, byte[]> files = ThemeResources.list("themes/" + theme + "/guacamole/", "guac-manifest.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

}