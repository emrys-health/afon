package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.TFUtils.finalizeIngress;
import static health.emrys.afon.cdk.TFUtils.envVar;
import static health.emrys.afon.infra.Defaults.JUPYTER_SERVICE_NAME;

public abstract class JupyterConfig implements JupyterDeployment {
    public static final  String          NO_JUPYTER_FINALIZERS = finalizeIngress("jupyter-ingress");
    /**
     * One in-pod X session per entry. Each session pairs a Guacamole login (used to
     * authenticate against the Guacamole web UI) with the VNC port serving that
     * user's Xvfb/x11vnc instance inside the Jupyter pod. start-vnc.sh in the image
     * must launch one Xvfb + x11vnc on each of these ports (display :N for port
     * 5900+N).
     */
    private final   List<GuacamoleConfig.Session> sessions;
    private final   TerraformStack                stack;
    private final   String                        jupyterPassword;
    protected final Properties                    properties;


    public JupyterConfig(TerraformStack stack,
                         Properties properties) {
        this.stack           = stack;
        this.jupyterPassword = AfonProperties.JUPYTER_VNC_PASSWORD.getValueFrom(properties);
        this.sessions        = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sessions.add(
                new GuacamoleConfig.Session(
                    "user" + i,
                    jupyterPassword,
                    5901 + i)
                        );
        }
        this.properties = properties;
    }

    public Map<String, String> deployJupyter(List<ITerraformDependable> eksDependOn) {
        final Map<String, String> jupyterLabels = Map.of("app", "jupyter");

        // Disable Jupyter's built-in token/password auth via env vars — oauth2-proxy handles authentication.
        // Using env vars avoids overriding the container CMD (which would break the tini entrypoint).
        DeploymentSpecTemplateSpecContainer jupyterContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("jupyter")
            .image("ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi")
            .env(List.of(
                     // Disable Jupyter's built-in auth so oauth2-proxy is the sole gate.
                     // NOTEBOOK_ARGS is read by start-notebook.sh and forwarded to the server,
                     // avoiding the need to override the tini entrypoint / CMD.
                     // Both NotebookApp (classic) and ServerApp (Jupyter >= 7) flags are included
                     // so this works regardless of which server the image uses.
                     envVar(
                         "NOTEBOOK_ARGS",
                         "--NotebookApp.token= --NotebookApp.password= " +
                         "--ServerApp.token= --ServerApp.password_required=False"),
                     envVar(
                         "VNC_SESSIONS", // see scripts/docker/jupyter/start-vnc.sh
                         "" + sessions.size())
                        )
                ).build();
        DeploymentUtils.deploy(
            stack,
            eksDependOn,
            jupyterLabels,
            List.of(jupyterContainer),
            List.of(),
            "jupyter");

        // Internal ClusterIP so oauth2-proxy can reach the Jupyter web UI.
        Service jupyterWeb = Service.Builder
            .create(stack, JUPYTER_SERVICE_NAME)
            .metadata(ServiceMetadata.builder()
                                     .name(JUPYTER_SERVICE_NAME)
                                     .namespace("default")
                                     .build())
            .dependsOn(eksDependOn)
            .provisioners(uponDestroyRun(NO_JUPYTER_FINALIZERS))
            .spec(ServiceSpec.builder()
                             .selector(jupyterLabels)
                             .type("ClusterIP")
                             .port(List.of(ServiceSpecPort.builder()
                                                          .name("web")
                                                          .port(8888)
                                                          .targetPort("8888")
                                                          .protocol("TCP")
                                                          .build()))
                             .build())
            .build();

        // Deploy oauth2-proxy pointing at the Jupyter web ClusterIP service.
        List<ITerraformDependable> proxyDeps = new ArrayList<>(eksDependOn);
        proxyDeps.add(jupyterWeb);
        getMFAConfig(proxyDeps);

        configureHttpsAndDNS();

        ServiceSpecPort sparkPort = ServiceSpecPort
            .builder()
            .name("spark")
            .port(7078) // Port the load balancer listens on
            .targetPort("7078") // Port the container is listening on
            .protocol("TCP")
            .build();
        // One Service port per per-user VNC instance (Xvfb + x11vnc) running inside
        // the Jupyter pod, so the Guacamole pod can reach each session by port.
        List<ServiceSpecPort> servicePorts = new ArrayList<>();
        servicePorts.add(sparkPort);
        for (GuacamoleConfig.Session s : sessions) {
            servicePorts.add(ServiceSpecPort
                                 .builder()
                                 .name("vnc-" + s.username())
                                 .port(s.vncPort())
                                 .targetPort(String.valueOf(s.vncPort()))
                                 .protocol("TCP")
                                 .build());
        }
        ServiceSpec networkSpec = ServiceSpec
            .builder()
            .selector(jupyterLabels) // Selects the Pods
            // created by secretAppDeployment
            .type("ClusterIP")
            .internalTrafficPolicy("Cluster")
            .clusterIp("None")
            .clusterIps(List.of())
            .port(servicePorts)
            .build();
        Service jupyterInternal = Service.Builder.create(stack, "jupyter-internal")
                                                 .metadata(ServiceMetadata
                                                               .builder()
                                                               .name("jupyter-internal")
                                                               .namespace("default")
                                                               .build())
                                                 .dependsOn(eksDependOn)
                                                 .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                 .spec(networkSpec)
                                                 .build();

        List<ITerraformDependable> guacamoleDeps = new ArrayList<>(eksDependOn);
        guacamoleDeps.add(jupyterInternal);
        new GuacamoleConfig(
            stack,
            guacamoleDeps,
            "jupyter-internal",
            jupyterPassword,
            sessions);

        return jupyterLabels;
    }

    protected abstract void configureHttpsAndDNS();

    protected abstract JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps);
}
