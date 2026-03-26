package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

/**
 * You can use this class to talk to instances that are not in the cluster with:
 * <pre>
 mvn -DPOLARIS_ENDPOINT=emryspolarisazure.click -Dexec.mainClass=health.emrys.afon.infra.LocalPolarisConfiguration exec:java -Dexec.args="AZURE_EXTERNAL POLARIS_ID:POLARIS_SECRET"
 * </pre>
 * although you will need to point your K8s current environment at the cloud and run
 * PolarisCredentialGrabber to get the POLARIS_ID and POLARIS_SECRET.
 */
public class LocalPolarisConfiguration extends AbstractPolarisConfiguration {

    public static void main(String[] args) throws Exception {
        LocalPolarisConfiguration app = new LocalPolarisConfiguration();
        parse(args, app);
    }

    private final String credentials;

    LocalPolarisConfiguration() {
        this(new AfonPropertiesLoader().getProperties());
    }

    public LocalPolarisConfiguration(String host) {
        this(new AfonPropertiesLoader().getProperties());
        properties.put(POLARIS_ENDPOINT.getPropertyName(), host);
    }

    public LocalPolarisConfiguration(Properties properties) {
        super(properties);
        credentials = POLARIS_ID.getValueFrom(properties) + ":" +  POLARIS_SECRET.getValueFrom(properties);
    }

    @Override
    protected String getCredentials() {
        return credentials;
    }

}
