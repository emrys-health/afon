package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;

/**
 * Given an endpoint, this class proxies Polaris.
 * You can use this class to talk to instances that are not in the cluster with:
 * <pre>
 ./afon.sh -a exec --exec-class ops.ConfigureEndpointPolarisMain --exec-args "AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET" --exec-envs "POLARIS_ENDPOINT=emryspolarisazure.click"
 * </pre>
 * although you will need to point your cluster environment at the cloud.
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
