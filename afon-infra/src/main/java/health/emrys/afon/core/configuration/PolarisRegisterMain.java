package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class PolarisRegisterMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisRegisterMain.class);

    static class PolarisRegisterMainConfig {
        final AfonProperties.SupportedCloudsEnum catalog;
        final String                             namespace;
        final String                             table;
        final String                             metadata;

        PolarisRegisterMainConfig(String[] args) {
            if (args.length != 4) {
                throw new IllegalArgumentException(format("""
                                                          arguments are: 
                                                          catalog namespace table metadata_location
                                                          was provided with %d arguments: %s 
                                                          """, args.length, Arrays.stream(args).collect(Collectors.joining(" "))));
            }
            catalog   = AfonProperties.SupportedCloudsEnum.valueOf(args[0]);
            namespace = args[1];
            table     = args[2];
            metadata  = args[3];
        }
    }

    public static void main(String[] args) {
        try (ClusterState state = new ClusterState()) {
            PolarisRegisterMainConfig conf        = new PolarisRegisterMainConfig(args);
            String                    endpoint    = format("https://%s", state.getPolarisHost());
            String                    accessToken = state.getCredentials();
            String[]                  creds       = accessToken.split(":");
            AfonPolarisClient client = new AfonPolarisClient(endpoint,
                                                             creds[0],
                                                             creds[1]);

            createNamespace(conf, client);
            createTable(conf, client);
            logger.info("Finished without error. Shutting down resources...");
        } catch (Exception e) {
            logger.error("Could not register table", e);
        }
    }

    private static void createTable(PolarisRegisterMainConfig conf, AfonPolarisClient client) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces/%s/register", conf.catalog, conf.namespace);
        String json = format("""
                                     {
                                         "name" : "%s",
                                         "metadata-location": "%s"
                                     }
                                     """, conf.table, conf.metadata);
        logger.info(json);
        ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
    }

    private static void createNamespace(PolarisRegisterMainConfig conf, AfonPolarisClient client) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces", conf.catalog);
        String json = format("""
                                     {
                                         "namespace" : ["%s"]
                                     }
                                     """, conf.namespace);
        logger.info(json);
        ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
    }

}
