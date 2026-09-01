package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;

/**
 * Given an endpoint, {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that
 * endpoint.  The endpoint is retrieved from the {@link AfonProperty#POLARIS_ENDPOINT} property.
 */
public class EndpointPolarisConfiguration extends PolarisConfiguration implements AutoCloseable {

    private final String credentials;

    public EndpointPolarisConfiguration(AfonPropertyProvider properties) {
        super(properties);
        credentials = polarisCredentials(properties);
    }

    @Override
    protected String getCredentials() {
        return credentials;
    }

    @Override
    public void close() throws Exception {
        // no-op
    }
}
