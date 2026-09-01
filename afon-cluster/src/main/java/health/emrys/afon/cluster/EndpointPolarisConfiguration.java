package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * Given an endpoint, {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that
 * endpoint.  The endpoint is retrieved from the {@link AfonProperty#POLARIS_ENDPOINT} property.
 *
 * The credentials are retrieved from the {@link AfonProperty#POLARIS_ID} and {@link AfonProperty#POLARIS_SECRET}.
 */
public class EndpointPolarisConfiguration extends PolarisConfiguration implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(EndpointPolarisConfiguration.class);

    private final String credentials;

    public EndpointPolarisConfiguration(AfonPropertyProvider properties) {
        super(properties);
        credentials = polarisCredentials(properties);
        logger.info("Using Polaris endpoint: {}", properties.getValue(POLARIS_ENDPOINT));
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
