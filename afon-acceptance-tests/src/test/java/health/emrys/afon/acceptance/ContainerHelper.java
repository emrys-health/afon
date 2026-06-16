package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.File;
import java.util.*;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
import static java.lang.String.format;
import static org.apache.polaris.core.persistence.bootstrap.RootCredentialsSet.ENVIRONMENT_VARIABLE;
import static org.slf4j.LoggerFactory.getLogger;

public class ContainerHelper {

    private static final Logger logger = getLogger(ContainerHelper.class);

    public static final ContainerHelper instance    = new ContainerHelper();
    public static final String          GOOGLE_FILE = "/tmp/application_default_credentials.json";

    /**
     * For testing.
     */
    public static void main(String[] args) {
        instance.startPolaris(new AfonPropertiesLoader().getProperties());
        System.out.println("Press enter to continue...");
        Scanner s = new Scanner(System.in);
        s.nextLine();
    }

    private GenericContainer<?> polaris;
    private GenericContainer<?> trino;

    private ContainerHelper() {
    }

    public synchronized void startPolaris(Properties properties) {
        if (polaris == null) {
            String googleCreds   = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties);
            String polarisId     = POLARIS_ID.getValueFrom(properties);
            String polarisSecret = POLARIS_SECRET.getValueFrom(properties);
            polaris = new GenericContainer<>(POLARIS_IMAGE_NAME)
                .withCommand("sh", "-c", POLARIS_CMD)
                .withEnv(ENVIRONMENT_VARIABLE, format("POLARIS,%s,%s", polarisId, polarisSecret))
                // GCP
                .withEnv("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_FILE)
                .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
            POLARIS_PROPERTIES.forEach((x) -> {
                polaris.withEnv(x.getPropertyName(), x.getValueFrom(properties));
            });
            Map.of(
                   "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_CATALOG_API__LEVEL", "DEBUG",
                   "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE__LEVEL", "DEBUG",
                   "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_EXCEPTION__LEVEL", "DEBUG",
                   "QUARKUS_LOG_CATEGORY__IO_QUARKUS_HTTP_ACCESS-LOG__LEVEL", "DEBUG"
                  )
               .entrySet()
               .forEach(x -> polaris.withEnv(x.getKey(), x.getValue()));

            polaris.setPortBindings(List.of("8181:8181", "8182:8182", DEBUG_PORT + ":" + DEBUG_PORT));
            logger.info("Starting Polaris docker container. This usually takes about 10s, longer if it's downloading " +
                        "the image.");
            polaris.start();
            polaris.copyFileToContainer(Transferable.of(googleCreds.getBytes()), GOOGLE_FILE);
        }
    }

    public synchronized void startTrino(Properties properties) {
        if (trino == null) {
            try {
                trino = new GenericContainer<>("trinodb/trino:latest");
                FileFinder fileFinder  = new FileFinder();
//                File       configDir   = fileFinder.fullPathOf("scripts/trino/catalog/");
                File       config      = fileFinder.fullPathOf("scripts/trino/config.properties");
                String     catalogPath = "/etc/trino/catalog";
//                trino.withFileSystemBind(configDir.getAbsolutePath(), catalogPath);
                trino.withFileSystemBind(config.getAbsolutePath(), "/etc/trino/config.properties");
                trino.setNetworkMode("host");
                trino.start();
                SupportedCloudsEnum catalog = SupportedCloudsEnum.AWS_TRINO;
                trino.copyFileToContainer(
                    Transferable.of(trinoLocalConfig(properties, catalog).getBytes()),
                    format("%s/%s.properties", catalogPath, catalog));
            } catch (Exception e) {
                throw new RuntimeException("Cannot start Trino container", e);
            }

        }
    }

    public static String trinoLocalConfig(Properties properties,
                                          SupportedCloudsEnum catalog) {
        String polarisId     = POLARIS_ID.getValueFrom(properties);
        String polarisSecret = POLARIS_SECRET.getValueFrom(properties);
        return format(
            """
            connector.name=iceberg
            iceberg.catalog.type=rest
            iceberg.rest-catalog.uri=http://%s/api/catalog/
            iceberg.rest-catalog.warehouse=%s
            iceberg.rest-catalog.vended-credentials-enabled=true
            
            iceberg.rest-catalog.security=OAUTH2
            iceberg.rest-catalog.oauth2.credential=%s:%s
            iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL
            
            fs.s3.enabled=true
            s3.region=%s
            """,
            POLARIS_ENDPOINT.getValueFrom(properties),
            catalog,
            polarisId,
            polarisSecret,
            AWS_REGION.getValueFrom(properties));
    }

}
