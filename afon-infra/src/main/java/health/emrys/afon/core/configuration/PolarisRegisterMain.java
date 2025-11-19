package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.JsonMaker;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static java.lang.String.format;

public class PolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisRegisterMain.class);

    static class PolarisRegisterMainConjig {
        final AfonProperties.SupportedCloudsEnum catalog;
        final String                             namespace;
        final String                             table;
        final String                             metadata;

        PolarisRegisterMainConjig(String[] args) {
            if (args.length != 4) {
                throw new IllegalArgumentException("arguments are: catalog namespace table metadata_location");
            }
            catalog   = AfonProperties.SupportedCloudsEnum.valueOf(args[0]);
            namespace = args[1];
            table     = args[2];
            metadata  = args[3];
        }
    }

    public static void main(String[] args) {
        try {
            PolarisRegisterMainConjig conf       = new PolarisRegisterMainConjig(args);
            ClusterState              state      = new ClusterState();
            AfonPropertiesLoader      loader     = new AfonPropertiesLoader();
            Properties                properties = loader.getProperties();
            String                    endpoint   = format("http://%s", state.getPolarisEndpoint());

            AfonPolarisClient client = new AfonPolarisClient(endpoint,
                                                             POLARIS_ID.getValueFrom(properties),
                                                             POLARIS_SECRET.getValueFrom(properties));
            String uri = format("/v1/%s/namespaces/%s/register", conf.catalog, conf.namespace);
            ImmutableRegisterTableRequest request = ImmutableRegisterTableRequest
                    .builder().name(conf.table).metadataLocation(conf.metadata).build();
            String json = new JsonMaker().toJson(request);
            client.postJsonCall(uri, json, state.getAccessToken());
        } catch (Exception e) {
            logger.error("Could not register table", e);
        }
    }

}
