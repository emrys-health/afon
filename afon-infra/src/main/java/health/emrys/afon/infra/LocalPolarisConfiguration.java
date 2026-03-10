package health.emrys.afon.infra;

import health.emrys.afon.config.AfonPropertiesLoader;

import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;

public class LocalPolarisConfiguration extends AbstractPolarisConfiguration {

    public static void main(String[] args) throws Exception {
        LocalPolarisConfiguration app = new LocalPolarisConfiguration();
        parse(args, app);
    }

    private final String credentials;

    LocalPolarisConfiguration() {
        super(new AfonPropertiesLoader().getProperties());
        credentials = POLARIS_ID.getValueFrom(properties) + ":" +  POLARIS_SECRET.getValueFrom(properties);
    }

    @Override
    protected String getCredentials() {
        return credentials;
    }

}
