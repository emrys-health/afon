package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
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
import static health.emrys.afon.cdk.PolarisConfig.NO_JUPYTER_FINALIZERS;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DNS_ZONE;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DOMAIN;
import static health.emrys.afon.infra.Defaults.JUPYTER_SERVICE_NAME;
import static java.lang.String.format;

public class JupyterConfig {
    /**
     * Port the Jupyter pod's in-pod VNC server listens on. The Jupyter image must run an
     * X server (e.g. Xvfb) plus a VNC server (e.g. x11vnc) bound to this port for the
     * Guacamole sidecar to be able to stream its GUI.
     */
    private final int            JUPYTER_VNC_PORT = 5901;
    private final TerraformStack stack;
    private final AwsConstruct   cloudProvider;
    private final String         jupyterDomain;
    private final String         jupyterPassword;
    private final String         jupyterDnsZoneId;

    public JupyterConfig(TerraformStack stack,
                         AwsConstruct cloudProvider,
                         Properties properties) {
        this.stack            = stack;
        this.cloudProvider    = cloudProvider;
        this.jupyterDomain    = AWS_JUPYTER_DOMAIN.getValueFrom(properties);
        this.jupyterPassword  = AfonProperties.JUPYTER_VNC_PASSWORD.getValueFrom(properties);
        this.jupyterDnsZoneId = AWS_JUPYTER_DNS_ZONE.getValueFrom(properties);
    }

    Map<String, String> deployJupyter(List<ITerraformDependable> eksDependOn) {
        final Map<String, String> jupyterLabels = Map.of("app", "jupyter");

        // Disable Jupyter's built-in token/password auth via env vars — oauth2-proxy handles authentication.
        // Using env vars avoids overriding the container CMD (which would break the tini entrypoint).
        DeploymentSpecTemplateSpecContainer jupyterContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("jupyter")
            .image("ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-noclip")
            .env(List.of(
                // Disable Jupyter's built-in auth so oauth2-proxy is the sole gate.
                // NOTEBOOK_ARGS is read by start-notebook.sh and forwarded to the server,
                // avoiding the need to override the tini entrypoint / CMD.
                // Both NotebookApp (classic) and ServerApp (Jupyter >= 7) flags are included
                // so this works regardless of which server the image uses.
                DeploymentSpecTemplateSpecContainerEnv
                    .builder()
                    .name("NOTEBOOK_ARGS")
                    .value("--NotebookApp.token= --NotebookApp.password= " +
                           "--ServerApp.token= --ServerApp" +
                           ".password_required=False")
                    .build()
                        ))
            .build();
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
        new JupyterMfaConfig(
            stack,
            proxyDeps,
            "http://guacamole-web:8080",
            jupyterDomain);

        // for emrysjupyter.click, this is Z03165251T5B540VMLNST
        // run aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click to see

        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → jupyter-web:8888 → Jupyter pod
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "jupyter-https-ingress",
            "jupyter-ingress",
            format(
                "arn:aws:acm:%s:%s:certificate/8f58f850-3756-40d4-b7b5-499124f150e3",
                cloudProvider.getRegion(),
                cloudProvider.getAccountId()),
            jupyterDomain,
            JupyterMfaConfig.PROXY_SVC_NAME,
            JupyterMfaConfig.PROXY_PORT,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            "JupyterAliasRecord",
            jupyterDnsZoneId, // aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click
            jupyterDomain, cloudProvider.getLbRelease(), ingress);

        ServiceSpecPort sparkPort = ServiceSpecPort
            .builder()
            .name("spark")
            .port(7078) // Port the load balancer listens on
            .targetPort("7078") // Port the container is listening on
            .protocol("TCP")
            .build();
        // Exposed so the in-cluster Guacamole pod can reach the Jupyter pod's VNC server
        // (Xvfb + x11vnc, or equivalent, must be running inside the Jupyter image).
        ServiceSpecPort vncPort = ServiceSpecPort
            .builder()
            .name("vnc")
            .port(JUPYTER_VNC_PORT)
            .targetPort(String.valueOf(JUPYTER_VNC_PORT))
            .protocol("TCP")
            .build();
        ServiceSpec networkSpec = ServiceSpec
            .builder()
            .selector(jupyterLabels) // Selects the Pods
            // created by secretAppDeployment
            .type("ClusterIP")
            .internalTrafficPolicy("Cluster")
            .clusterIp("None")
            .clusterIps(List.of())
            .port(List.of(sparkPort, vncPort))
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
            JUPYTER_VNC_PORT,
            jupyterPassword);

        return jupyterLabels;
    }

}
