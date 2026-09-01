package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;

/**
 * Given an endpoint, {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that
 * endpoint.
 *
 * An example of usage of this {@link PolarisConfiguration} is:
 * <pre>
 ./afon.sh -a exec --exec-class ops.ConfigureEndpointPolarisMain
                    --exec-args "AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET"
                    --exec-envs "POLARIS_ENDPOINT=emryspolarisazure.click"
 * </pre>
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
