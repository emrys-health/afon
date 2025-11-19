package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.slf4j.Logger;

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
            PolarisRegisterMainConjig conf        = new PolarisRegisterMainConjig(args);
            ClusterState              state       = new ClusterState();
            String                    endpoint    = format("http://%s", state.getPolarisEndpoint());
            String                    accessToken = state.getAccessToken();
            String[]                  creds       = accessToken.split(":");
            AfonPolarisClient         client      = new AfonPolarisClient(endpoint,
                                                             creds[0],
                                                             creds[1]);
            String uri = format("/api/catalog/v1/%s/namespaces/%s/register", conf.catalog, conf.namespace);
            ImmutableRegisterTableRequest request = ImmutableRegisterTableRequest
                    .builder().name(conf.table).metadataLocation(conf.metadata).build();
//            String json = new JsonMaker().toJson(request);
//            String json = jakarta.ws.rs.client.Entity.json(request);
            String json = format("""
                    {
                        "name" : "%s",
                        "metadata-location": "%s"
                    }
                    """, conf.table, conf.metadata);
            logger.info(json);
            client.postJsonCall(uri, json, client.getAccessToken());
        } catch (Exception e) {
            logger.error("Could not register table", e);
        }
    }

}
