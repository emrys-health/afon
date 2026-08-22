package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;

/**
 * Given an endpoint, this class proxies Polaris.
 * You can use this class to talk to instances that are not in the cluster with:
 * <pre>
 mvn -DPOLARIS_ENDPOINT=emryspolarisazure.click -Dexec.mainClass=health.emrys.afon.cluster.EndpointPolarisConfiguration exec:java -Dexec.args="AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET"
 * </pre>
 * although you will need to point your K8s current environment at the cloud.
 */
public class EndpointPolarisConfiguration extends AbstractPolarisConfiguration implements AutoCloseable {

    private final String credentials;

    public EndpointPolarisConfiguration(AfonPropertyProvider properties) {
        super(properties);
        credentials = properties.getValue(POLARIS_ID) + ":" +  properties.getValue(POLARIS_SECRET);
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
