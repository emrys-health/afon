package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static health.emrys.afon.config.AfonPropertiesLoader.add;

public abstract class AbstractPolarisConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(AbstractPolarisConfiguration.class);

    protected static final Map<AfonProperties.SupportedCloudsEnum, Function<Properties, CloudConfigForPolaris>>
                                      CLOUD_TO_PROVIDER = Map.of(
        AfonProperties.SupportedCloudsEnum.AWS, AwsCloudConfigForPolaris::new,
        AfonProperties.SupportedCloudsEnum.AZURE, AzureCloudConfigForPolaris::new,
        AfonProperties.SupportedCloudsEnum.GCP, GoogleCloudConfigForPolaris::new,
        AfonProperties.SupportedCloudsEnum.AZURE_EXTERNAL, ExternalAzureCloudConfigForPolaris::new
                                                                );
    protected final          Properties properties;
    private final PolarisConfigureMain polarisClient;

    protected AbstractPolarisConfiguration(Properties properties) {
        this.properties = properties;
        polarisClient   = new PolarisConfigureMain();
    }

    protected static void parse(String[] args,
                                AbstractPolarisConfiguration app)
    throws IncorrectConfigException, IOException {
        if (args.length == 0) {
            app.initializePolaris();
        } else if (args.length == 1) {
            AfonProperties.SupportedCloudsEnum cloud = AfonProperties.SupportedCloudsEnum.valueOf(args[0]);
            app.initialize(cloud, app.properties);
        } else if (args.length == 2) {
            AfonProperties.SupportedCloudsEnum cloud    = AfonProperties.SupportedCloudsEnum.valueOf(args[0]);
            Properties                         external = new Properties();
            external.putAll(app.properties);
            String credential = args[1];
            logger.info("Credential: " + credential);
            app.initialize(cloud, add(credential, external));
        }
    }

    public void initialize(AfonProperties.SupportedCloudsEnum cloud, Properties properties) throws IncorrectConfigException, IOException {
        CloudConfigForPolaris cloudConfig = CLOUD_TO_PROVIDER.get(cloud).apply(properties);
        initializePolaris(new CloudConfigForPolaris[]{cloudConfig});
    }

    public void initializePolaris() throws IncorrectConfigException, IOException {
        polarisClient.createAllCatalogs(properties, getCredentials());
    }

    public void initializePolaris(CloudConfigForPolaris[] configs) throws IncorrectConfigException, IOException {
        polarisClient.createCatalogs(properties, getCredentials(), configs);
    }

    abstract protected String getCredentials();

}
