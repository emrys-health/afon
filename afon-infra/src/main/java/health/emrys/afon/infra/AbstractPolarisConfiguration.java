package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.config.PolarisConfigClient;
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
    private final PolarisConfigClient polarisClient;

    protected AbstractPolarisConfiguration(Properties properties) {
        this.properties = properties;
        polarisClient   = new PolarisConfigClient();
    }

    public void configure(String[] args)
    throws IncorrectConfigException, IOException {
        logger.info("Calling {} with arguments {}", getClass().getSimpleName(), String.join(" ", args));
        if (args.length == 0) {
            initializePolaris();
        } else if (args.length == 1) {
            SupportedCloudsEnum cloud = SupportedCloudsEnum.valueOf(args[0]);
            initialize(cloud, properties);
        } else if (args.length == 2) {
            SupportedCloudsEnum cloud    = SupportedCloudsEnum.valueOf(args[0]);
            Properties          external = new Properties();
            external.putAll(properties);
            String credential = args[1];
            logger.info("Credential: " + credential);
            initialize(cloud, add(credential, external));
        }
    }

    public List<HttpResponse> checkResponses(List<HttpResponse> responses) {
        logger.info("HTTP responses = {}", responses);
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
            if (!badResponses.isEmpty()) {
                throw new RuntimeException(badResponses.toString());
            }
        }
        return responses;
    }

    public List<HttpResponse> initialize(SupportedCloudsEnum cloud) throws IncorrectConfigException, IOException {
        return initialize(cloud, properties);
    }

    public List<HttpResponse> initialize(SupportedCloudsEnum cloud, Properties properties) throws IncorrectConfigException, IOException {
        CloudConfigForPolaris cloudConfig = CLOUD_TO_PROVIDER.get(cloud).apply(properties);
        return initializePolaris(new CloudConfigForPolaris[]{cloudConfig});
    }

    public List<HttpResponse> initializePolaris() throws IncorrectConfigException, IOException {
        return checkResponses(polarisClient.createAllCatalogs(properties, getCredentials()));
    }

    public List<HttpResponse> initializePolaris(CloudConfigForPolaris[] configs) throws IncorrectConfigException, IOException {
        return checkResponses(polarisClient.createCatalogs(properties, getCredentials(), configs));
    }

    abstract protected String getCredentials();

}
