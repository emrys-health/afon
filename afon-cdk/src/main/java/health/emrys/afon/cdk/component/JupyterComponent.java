package health.emrys.afon.cdk.component;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.JupyterHubConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * JupyterHub with Guacamole, behind the oauth2-proxy MFA gate, which is exposed over HTTPS at the cloud's
 * {@code JUPYTER_DOMAIN_NAME}.  It waits for the KeyCloak of the stack, if it has one, as the hub and the proxy do
 * OIDC discovery on startup.
 */
public class JupyterComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        List<ITerraformDependable> issuerDependencies = context.find(KeycloakComponent.class)
                                                              .map(keycloak -> List.of(keycloak.dnsRecord()))
                                                              .orElse(List.of());
        JupyterHubConfig jupyter = new JupyterHubConfig(
            context.stack(), platform, context.properties(), issuerDependencies);
        // Every Service the hub deploy creates fires the cloud's mutating webhooks, if it has any, which the
        // service dependables wait for
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        dependsOn.addAll(platform.getServiceDependables());
        jupyter.deployJupyterHub(dependsOn);
    }
}
