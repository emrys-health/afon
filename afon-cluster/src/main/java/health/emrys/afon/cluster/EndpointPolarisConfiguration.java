package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertiesLoader;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

/**
 * Given an endpoint, this class proxies Polaris.
 * You can use this class to talk to instances that are not in the cluster with:
 * <pre>
 mvn -DPOLARIS_ENDPOINT=emryspolarisazure.click -Dexec.mainClass=health.emrys.afon.cluster.EndpointPolarisConfiguration exec:java -Dexec.args="AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET"
 * </pre>
 * although you will need to point your K8s current environment at the cloud and run
 * PolarisCredentialGrabber to get the POLARIS_ID and POLARIS_SECRET.
 */
public class EndpointPolarisConfiguration extends AbstractPolarisConfiguration implements AutoCloseable {

    private final String credentials;

    public EndpointPolarisConfiguration() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public EndpointPolarisConfiguration(String host) {
        this(new AfonPropertiesLoader().getProperties());
        properties.put(POLARIS_ENDPOINT.getName(), host);
    }

    public EndpointPolarisConfiguration(Properties properties) {
        super(properties);
        credentials = POLARIS_ID.getValueFrom(properties) + ":" +  POLARIS_SECRET.getValueFrom(properties);
    }

    @Override
    protected String getCredentials() {
        return credentials;
    }

    @Override
    public void close() throws Exception {

    }
}
