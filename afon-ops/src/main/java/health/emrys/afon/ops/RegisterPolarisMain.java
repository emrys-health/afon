package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.cluster.ClusterState;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class RegisterPolarisMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterPolarisMain.class);

    public static class RegisterTableConfig {

        static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterTableConfig.class);

        private final AfonProperties.SupportedCloudsEnum catalog;
        private final String              namespace;
        private final String              table;
        private final String              metadata;

        RegisterTableConfig(String[] args) {
            if (args.length < 4) {
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

        ClassicHttpResponse createTable(AfonPolarisClient client) throws IOException {
            String uri = format("/api/catalog/v1/%s/namespaces/%s/register", catalog, namespace);
            String json = format("""
                                         {
                                             "name" : "%s",
                                             "metadata-location": "%s"
                                         }
                                         """, table, metadata);
            logger.info(json);
            ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
            logger.info("response: {}", response.getCode());
            return response;
        }

        ClassicHttpResponse createNamespace(AfonPolarisClient client) throws IOException {
            String uri = format("/api/catalog/v1/%s/namespaces", catalog);
            String json = format("""
                                         {
                                             "namespace" : ["%s"]
                                         }
                                         """, namespace);
            logger.info(json);
            ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
            logger.info("response: {}", response.getCode());
            return response;
        }

        public String getTable() {
            return table;
        }

        public String getNamespace() {
            return namespace;
        }

        public AfonProperties.SupportedCloudsEnum getCatalog() {
            return catalog;
        }
    }

    public static void main(String[] args) {
        final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
        try (ClusterState state = new ClusterState()) {
            RegisterTableConfig conf        = new RegisterTableConfig(args);
            String              endpoint    = format("https://%s", state.getPolarisHost());
            String              accessToken = state.getCredentials();
            String[]            creds       = accessToken.split(":");
            AfonPolarisClient client = clientFactory.getPolarisClient(
                    endpoint,
                    creds[0],
                    creds[1]);

            conf.createNamespace(client);
            conf.createTable(client);
            logger.info("Finished without error. Shutting down resources...");
        } catch (Exception e) {
            logger.error("Failed to register table", e);
        }
    }


}
