package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import software.constructs.Construct;

import java.util.List;

/**
 * Deploys oauth2-proxy in front of Jupyter to enforce MFA via an OIDC identity provider.
 *
 * <p>Traffic flow:
 * <pre>
 *   AWS LoadBalancer (port 80) ─► oauth2-proxy pod (port 4180) ─► jupyter-web ClusterIP (port 8888) ─► Jupyter pod
 * </pre>
 *
 * <p>The oauth2-proxy wiring itself is the shared {@link AuthProxyConfig};
 * this subclass only pins Jupyter's historical construct ids and Kubernetes
 * names so existing deployments see no diff.
 */
public class JupyterMfaConfig extends AuthProxyConfig {

    public static final int    PROXY_PORT     = AuthProxyConfig.PROXY_PORT;
    public static final String PROXY_SVC_NAME = "oauth2-proxy";

    /** Jupyter's pre-{@link AuthProxyConfig} names, kept for synth parity. */
    private static final Names LEGACY_NAMES =
        new Names(PROXY_SVC_NAME, "oauth2ProxySecret", "oauth2-proxy-secret");

    public JupyterMfaConfig(Construct scope,
                     List<ITerraformDependable> dependsOn,
                     String jupyterUpstreamUrl,
                     String jupyterDomain,
                     String clientId,
                     String clientSecret,
                     String cookieSecret) {
        this(scope, dependsOn, List.of(jupyterUpstreamUrl), jupyterDomain, clientId, clientSecret, cookieSecret);
    }

    /**
     * Variant accepting several upstreams. oauth2-proxy mounts each upstream at the path component
     * of its URL (longest-prefix match), so a single MFA gate can front several apps on one domain
     * — e.g. JupyterHub at {@code /} and Guacamole at {@code /guacamole/}.
     */
    public JupyterMfaConfig(Construct scope,
                     List<ITerraformDependable> dependsOn,
                     List<String> upstreamUrls,
                     String jupyterDomain,
                     String clientId,
                     String clientSecret,
                     String cookieSecret) {
        super(scope, dependsOn, LEGACY_NAMES, upstreamUrls, jupyterDomain,
              clientId, clientSecret, cookieSecret, null);
    }
}
