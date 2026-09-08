package health.emrys.afon.trino;

import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;

/**
 * A {@link TrinoServerConfiguration} for a Polaris running in external-auth mode (KeyCloak as the
 * OIDC identity provider). Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so Trino's Iceberg REST catalog must
 * obtain its OAuth2 token directly from KeyCloak.
 *
 * <p>This overrides {@link #catalogProperties} to point the catalog at KeyCloak via
 * {@code iceberg.rest-catalog.oauth2.server-uri} and to request the {@code openid} scope - KeyCloak
 * rejects Polaris' "PRINCIPAL_ROLE:ALL" scope (and Iceberg's default "catalog") with invalid_scope.
 * The credentials supplied to the superclass must identify a client KeyCloak knows about.
 */
public class KeyCloakTrinoServerConfiguration extends TrinoServerConfiguration {

    private static final String KEYCLOAK_SCOPE = "openid";

    private final String oauth2ServerUri;

    public KeyCloakTrinoServerConfiguration(AfonPropertyProvider properties,
                                            String polarisDomainName,
                                            String oauth2ServerUri) {
        super(properties, polarisDomainName);
        this.oauth2ServerUri = oauth2ServerUri;
    }

    @Override
    protected @NonNull String getOauthScope() {
        return KEYCLOAK_SCOPE;
    }

    @Override
    protected String additionalProperties() {
        return "iceberg.rest-catalog.oauth2.server-uri=" + oauth2ServerUri;
    }
}