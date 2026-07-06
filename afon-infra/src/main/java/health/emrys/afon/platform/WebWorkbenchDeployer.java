package health.emrys.afon.platform;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.AuthProxyConfig;
import health.emrys.afon.cdk.DeploymentUtils;
import health.emrys.afon.config.deployment.AuthSpec;
import health.emrys.afon.config.deployment.WorkbenchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Template for workbenches that are a single web UI container (VS Code,
 * RStudio) — as opposed to Jupyter's Guacamole/VNC stack. Synthesises:
 * <pre>
 *   ingress (cloud-specific) ─► oauth2-proxy ({@link AuthProxyConfig}) ─► &lt;type&gt;-web ClusterIP ─► workbench pod
 * </pre>
 * The workbench's own authentication is disabled by the subclass's container
 * definition; oauth2-proxy is the sole gate, exactly as with Jupyter.
 */
public abstract class WebWorkbenchDeployer implements WorkbenchDeployer {

    /** The workbench container with its internal auth disabled. */
    protected abstract DeploymentSpecTemplateSpecContainer container();

    /** The port the workbench's web UI listens on inside the pod. */
    protected abstract int port();

    @Override
    public void deploy(ProvisionedCloud target, WorkbenchSpec spec, DeploymentContext ctx) {
        String              name    = workbenchType();
        String              webSvc  = name + "-web";
        String              proxy   = name + "-oauth2-proxy";
        Map<String, String> labels  = Map.of("app", name);
        TerraformStack      stack   = target.stack();

        List<ITerraformDependable> deps = target.workloadReadiness();
        DeploymentUtils.deploy(stack, deps, labels, List.of(container()), List.of(), name);

        // Internal ClusterIP so oauth2-proxy can reach the workbench's web UI.
        Service web = Service.Builder
            .create(stack, webSvc)
            .metadata(ServiceMetadata.builder()
                                     .name(webSvc)
                                     .namespace("default")
                                     .build())
            .dependsOn(deps)
            .spec(ServiceSpec.builder()
                             .selector(labels)
                             .type("ClusterIP")
                             .port(List.of(ServiceSpecPort.builder()
                                                          .name("web")
                                                          .port(port())
                                                          .targetPort(String.valueOf(port()))
                                                          .protocol("TCP")
                                                          .build()))
                             .build())
            .build();

        List<ITerraformDependable> proxyDeps = new ArrayList<>(deps);
        proxyDeps.add(web);

        AuthSpec auth = requireAuth(spec);
        new AuthProxyConfig(
            stack,
            proxyDeps,
            AuthProxyConfig.Names.of(proxy),
            "http://" + webSvc + ":" + port(),
            spec.domain(),
            resolveRef(auth.clientIdRef(), spec, ctx),
            resolveRef(auth.clientSecretRef(), spec, ctx),
            resolveRef(auth.cookieSecretRef(), spec, ctx),
            auth.emailDomain());

        target.exposeWorkbench(name + "-ingress", spec.domain(), proxy, AuthProxyConfig.PROXY_PORT);
    }

    private AuthSpec requireAuth(WorkbenchSpec spec) {
        if (spec.auth() == null) {
            throw new IllegalArgumentException(
                "Workbench '" + workbenchType() + "' requires an auth: block (oauth2-proxy is its only gate)");
        }
        return spec.auth();
    }

    /** Resolves a {@code *Ref} key against the secrets-carrying properties. */
    private String resolveRef(String ref, WorkbenchSpec spec, DeploymentContext ctx) {
        if (ref == null) {
            throw new IllegalArgumentException(
                "Workbench '" + workbenchType() + "' auth block must set clientIdRef, clientSecretRef and cookieSecretRef");
        }
        String value = ctx.properties().getProperty(ref);
        if (value == null) {
            throw new IllegalArgumentException(
                "Property " + ref + " (referenced by workbench '" + workbenchType() + "') not found");
        }
        return value;
    }
}
