package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.*;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.OIDC_PROTOCOL;
import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.security.SecurityUtils.KEYCLOAK_SCOPE;

abstract public class ExternalPolarisCatalog extends PolarisCatalog {
    private final @NonNull String clientId;
    private final @NonNull String clientSecret;
    private final @NonNull String tokenUri;

    /**
     * The Polaris hosting the catalog defers to KeyCloak for OAuth, so the Polaris federating to it gets its token there, not from
     * the {@code /api/catalog/v1/oauth/tokens} of the remote Polaris, which answers HTTP 501, and requests the scope KeyCloak accepts.
     *
     * @param remoteCloud the cloud hosting the remote Polaris, whose KeyCloak authenticates the call: each cloud may have its own
     */
    protected ExternalPolarisCatalog(AfonPropertyProvider properties, AfonCloud remoteCloud) {
        clientId = properties.getValue(POLARIS_ID);
        clientSecret = ***REMOVED***
        tokenUri = KeyCloakConfigurer.tokenEndpoint(properties.getOidcDomainName(remoteCloud), properties.getValue(OIDC_PROTOCOL, "https"));
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog();
        return toJson(catalog);
    }

    @Override
    protected @NonNull Catalog createCatalog() {
        OAuthClientCredentialsParameters credentialsParameters = OAuthClientCredentialsParameters
            .builder()
            .setAuthenticationType(AuthenticationParameters.AuthenticationTypeEnum.OAUTH)
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setTokenUri(tokenUri)
            .setScopes(scopes())
            .build();

        CatalogProperties bucketProperties = CatalogProperties
            .builder()
            .addProperty("warehouse", remoteCatalogName()) // corresponds to org.apache.iceberg.CatalogProperties.WAREHOUSE_LOCATION)
            .setDefaultBaseLocation(bucketUri())
            .build();

        ConnectionConfigInfo connectionInfo = new ConnectionConfigInfo(
            ConnectionConfigInfo.ConnectionTypeEnum.ICEBERG_REST,
            endpoint() + "/api/catalog/",
            credentialsParameters,
            null,
            Map.of());

        Catalog catalog =
            new ExternalCatalog(
                connectionInfo,
                Catalog.TypeEnum.EXTERNAL,
                catalogName(),
                bucketProperties,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                null,
                storageConfigInfo());
        return catalog;
    }

    protected abstract String remoteCatalogName();

    protected List<String> scopes() {
        return List.of(KEYCLOAK_SCOPE);
    }

    protected abstract @NonNull String endpoint();

}
