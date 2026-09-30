package health.emrys.afon.polaris.keycloak;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.protocolFor;
import static java.lang.String.format;

/**
 * An {@link PolarisRestClient} for a Polaris configured to use KeyCloak as an external OIDC identity
 * provider. In that mode Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so the access token is obtained directly
 * from KeyCloak, via {@link IdentityProvider#clientToken}. The inherited catalog/management calls then
 * present it to Polaris as a bearer token, and {@link #getAccessToken()} retries it on transient failures.
 *
 * <p>To get the same token from the command line: {@code ./afon.sh -a show-oidc-token <cloud>}.
 */
public class KeyCloakPolarisRestClient extends PolarisRestClient {

    private final IdentityProvider identityProvider;

    public KeyCloakPolarisRestClient(AfonPropertyProvider properties) {
        this(properties, protocolFor(properties.getValue(POLARIS_ENDPOINT)));
    }

    public KeyCloakPolarisRestClient(AfonPropertyProvider properties, String protocol) {
        this(properties, protocol, new KeyCloakProvider(properties.getValue(OIDC_DOMAIN_NAME)));
    }

    KeyCloakPolarisRestClient(AfonPropertyProvider properties, String protocol, IdentityProvider identityProvider) {
        super(format("%s://%s", protocol, properties.getValue(POLARIS_ENDPOINT)),
                properties.getValue(POLARIS_ID),
                properties.getValue(POLARIS_SECRET));
        this.identityProvider = identityProvider;
    }

    @Override
    protected String accessToken() throws IOException {
        return identityProvider.clientToken(clientId, clientSecret);
    }

}
