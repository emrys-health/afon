package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class JupyterKeyCloakConfig extends JupyterMfaConfig {
    private static final Map<String, String> PROXY_LABELS = Map.of("app", "keycloak");

    public JupyterKeyCloakConfig(Construct scope,
                                 List<ITerraformDependable> dependsOn,
                                 String jupyterUpstreamUrl,
                                 String jupyterDomain,
                                 String clientId,
                                 String clientSecret,
                                 String cookieSecret) {
        super(scope, dependsOn, jupyterUpstreamUrl, jupyterDomain, clientId, clientSecret, cookieSecret);
        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("keycloak")
            .image("quay.io/keycloak/keycloak:26.6.3")
            .env(List.of())
            .build();

        DeploymentUtils.deploy(scope, dependsOn, PROXY_LABELS, List.of(proxyContainer), List.of(), "keycloak");
        // TODO
    }

    @Override
    protected @NonNull List<String> emailArgs(Properties properties) {
        return List.of();
    }
}
