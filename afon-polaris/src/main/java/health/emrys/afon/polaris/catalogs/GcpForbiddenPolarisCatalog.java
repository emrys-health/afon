package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.GCP_BUCKET_NAME_FORBIDDEN;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class GcpForbiddenPolarisCatalog extends GcpPolarisCatalog {

    private final String bucketUrl;

    public GcpForbiddenPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = gsBucketUrl(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
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
        return "gcp_forbidden";
    }
}
