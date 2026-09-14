package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.AZURE_BUCKET_NAME_FORBIDDEN;
import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;

public class AzureForbiddenPolarisCatalog extends AzurePolarisCatalog {

    private final String bucketUrl;

    public AzureForbiddenPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = abfssBucketUrl(properties.getValue(AZURE_BUCKET_NAME_FORBIDDEN), properties.getValue(AZURE_STORAGE_ACCOUNT));
    }

    @Override
    public boolean adminGrants() {
        return false;
    }

    @Override
    protected String bucketUri() {
        return bucketUrl;
    }

    @Override
    public String catalogName() {
        return "azure_forbidden";
    }
}
