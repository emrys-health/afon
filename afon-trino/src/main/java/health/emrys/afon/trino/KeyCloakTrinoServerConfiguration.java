package health.emrys.afon.trino;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FileUserProvider;
import org.jspecify.annotations.NonNull;

import static health.emrys.afon.security.SecurityUtils.KEYCLOAK_SCOPE;

/**
 * A {@link TrinoServerConfiguration} for a Polaris running in external-auth mode (KeyCloak as the
 * OIDC identity provider). Polaris no longer implements its own token endpoint
 * ({@code /api/catalog/v1/oauth/tokens} returns HTTP 501), so Trino's Iceberg REST catalog must
 * obtain its OAuth2 token directly from KeyCloak.
 *
 * <p>This overrides {@link #catalogProperties} to point the catalog at KeyCloak via
 * {@code iceberg.rest-catalog.oauth2.server-uri} and to request the {@code openid} scope - KeyCloak
 * rejects Polaris' "PRINCIPAL_ROLE:ALL" scope (and Iceberg's default "catalog") with invalid_scope.
 * The credentials are those of the given {@link AfonUser}, which KeyCloak knows as a confidential
 * client of the same name and secret.
 */
public class KeyCloakTrinoServerConfiguration extends TrinoServerConfiguration {


    private final String oauth2ServerUri;

    /**
     * Authenticates as the configured user, from users.csv, named by {@code USER_ID}.
     */
    public KeyCloakTrinoServerConfiguration(AfonPropertyProvider properties,
                                            String polarisDomainName,
                                            String oauth2ServerUri) {
        this(properties, polarisDomainName, oauth2ServerUri, new FileUserProvider().getConfiguredUser(properties));
    }

    public KeyCloakTrinoServerConfiguration(AfonPropertyProvider properties,
                                            String polarisDomainName,
                                            String oauth2ServerUri,
                                            AfonUser user) {
        super(properties, polarisDomainName, user);
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