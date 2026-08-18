package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonProperty;
import org.apache.polaris.core.admin.model.*;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.polaris.config.PolarisConfigurer.tokensURI;

abstract public class ExternalCloudConfigForPolaris extends CloudConfigForPolaris {
    private final @NonNull String clientId;
    private final @NonNull String secret;

    protected ExternalCloudConfigForPolaris(Properties properties) {
        clientId                = AfonProperty.POLARIS_ID.getValueFrom(properties);
        secret                  = AfonProperty.POLARIS_SECRET.getValueFrom(properties);
    }
    @Override
    public String catalogConfig() throws IncorrectConfigException {
        OAuthClientCredentialsParameters cnxnParams = OAuthClientCredentialsParameters
            .builder()
            .setAuthenticationType(AuthenticationParameters.AuthenticationTypeEnum.OAUTH)
            .setClientId(clientId)
            .setClientSecret(secret)
            .setTokenUri(tokensURI(getEndpoint()))
            .setScopes(getScopes())
            .build();

        CatalogProperties bucketProperties = CatalogProperties
            .builder()
            .addProperty("warehouse", getRemoteCatalogName()) // corresponds to org.apache.iceberg.CatalogProperties.WAREHOUSE_LOCATION)
            .setDefaultBaseLocation(getBucketUri())
            .build();

        ConnectionConfigInfo connectionInfo = new ConnectionConfigInfo(
            ConnectionConfigInfo.ConnectionTypeEnum.ICEBERG_REST,
            getEndpoint() + "/api/catalog/",
            cnxnParams,
            null,
            Map.of());

        ExternalCatalog catalog =
            new ExternalCatalog(
                connectionInfo,
                Catalog.TypeEnum.EXTERNAL,
                warehouseName(),
                bucketProperties,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                null,
                getStorageConfigInfo());
        return toJson(catalog);
    }

    protected abstract String getRemoteCatalogName();

    protected abstract List<String> getScopes();

    protected abstract @NonNull String getEndpoint();

}
