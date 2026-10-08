package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.StackComponent;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.configs.KeyCloakConfig;

/**
 * KeyCloak, the OIDC provider, behind its HTTPS ingress and DNS record at {@code OIDC_DOMAIN_NAME}.  A stack without
 * it uses the KeyCloak at that domain name which another stack deploys.
 */
public class KeycloakComponent implements StackComponent {

    private KeyCloakConfig keyCloakConfig;

    @Override
    public void deploy(StackContext context) {
        keyCloakConfig = new KeyCloakConfig(context.stack(), context.properties(), context.platform());
        keyCloakConfig.configureHttpsAndDNS();
    }

    /**
     * @return the DNS record making the KeyCloak hostname, and hence the OIDC issuer, resolvable, which what does OIDC
     * discovery on startup must wait for
     */
    public ITerraformDependable dnsRecord() {
        return keyCloakConfig.getDnsRecord();
    }
}
