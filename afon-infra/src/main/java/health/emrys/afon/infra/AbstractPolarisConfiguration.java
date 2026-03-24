package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static health.emrys.afon.config.AfonPropertiesLoader.add;

public abstract class AbstractPolarisConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(AbstractPolarisConfiguration.class);

    protected static final Map<SupportedCloudsEnum, Function<Properties, CloudConfigForPolaris>>
                                      CLOUD_TO_PROVIDER = Map.of(
        SupportedCloudsEnum.AWS, AwsCloudConfigForPolaris::new,
        SupportedCloudsEnum.AZURE, AzureCloudConfigForPolaris::new,
        SupportedCloudsEnum.GCP, GoogleCloudConfigForPolaris::new,
        SupportedCloudsEnum.AZURE_EXTERNAL, ExternalAzureCloudConfigForPolaris::new,
        SupportedCloudsEnum.GCP_EXTERNAL, ExternalGcpCloudConfigForPolaris::new
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
            SupportedCloudsEnum cloud = SupportedCloudsEnum.valueOf(args[0]);
            app.initialize(cloud, app.properties);
        } else if (args.length == 2) {
            SupportedCloudsEnum cloud    = SupportedCloudsEnum.valueOf(args[0]);
            Properties          external = new Properties();
            external.putAll(app.properties);
            String credential = args[1];
            logger.info("Credential: " + credential);
            app.initialize(cloud, add(credential, external));
        }
    }

    public List<HttpResponse> initialize(SupportedCloudsEnum cloud, Properties properties) throws IncorrectConfigException, IOException {
        CloudConfigForPolaris cloudConfig = CLOUD_TO_PROVIDER.get(cloud).apply(properties);
        return initializePolaris(new CloudConfigForPolaris[]{cloudConfig});
    }

    public List<HttpResponse> initializePolaris() throws IncorrectConfigException, IOException {
        return polarisClient.createAllCatalogs(properties, getCredentials());
    }

    public List<HttpResponse> initializePolaris(CloudConfigForPolaris[] configs) throws IncorrectConfigException, IOException {
        return polarisClient.createCatalogs(properties, getCredentials(), configs);
    }

    abstract protected String getCredentials();

}
