package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.*;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.polaris.config.PolarisRestClient.tokensURI;

abstract public class ExternalPolarisCatalog extends PolarisCatalog {
    private final @NonNull String clientId;
    private final @NonNull String clientSecret;

    protected ExternalPolarisCatalog(AfonPropertyProvider properties) {
        clientId = properties.getValue(POLARIS_ID);
        clientSecret = ***REMOVED***
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog();
        return toJson(catalog);
    }

    protected @NonNull Catalog createCatalog() {
        OAuthClientCredentialsParameters credentialsParameters = OAuthClientCredentialsParameters
            .builder()
            .setAuthenticationType(AuthenticationParameters.AuthenticationTypeEnum.OAUTH)
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setTokenUri(tokensURI(endpoint()))
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

    protected abstract List<String> scopes();

    protected abstract @NonNull String endpoint();

}
