package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperty;
import org.apache.polaris.core.admin.model.*;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.polaris.config.PolarisConfigurer.tokensURI;

abstract public class ExternalPolarisCatalog extends PolarisCatalog {
    private final @NonNull String clientId;
    private final @NonNull String secret;

    protected ExternalPolarisCatalog(Properties properties) {
        clientId                = AfonProperty.POLARIS_ID.getValueFrom(properties);
        secret                  = AfonProperty.POLARIS_SECRET.getValueFrom(properties);
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
            .setClientSecret(secret)
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
