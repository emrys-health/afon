package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class RegisterTableConfig {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(RegisterTableConfig.class);

    private final SupportedCloudsEnum catalog;
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
        catalog   = SupportedCloudsEnum.valueOf(args[0]);
        namespace = args[1];
        table     = args[2];
        metadata  = args[3];
    }

    void createTable(AfonPolarisClient client) throws IOException {
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
    }

    void createNamespace(AfonPolarisClient client) throws IOException {
        String uri = format("/api/catalog/v1/%s/namespaces", catalog);
        String json = format("""
                                     {
                                         "namespace" : ["%s"]
                                     }
                                     """, namespace);
        logger.info(json);
        ClassicHttpResponse response = client.postJsonCall(uri, json, client.getAccessToken());
        logger.info("response: {}", response.getCode());
    }
}
