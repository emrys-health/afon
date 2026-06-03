package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
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
        instance.start(new AfonPropertiesLoader().getProperties());
        System.out.println("Press enter to continue...");
        Scanner s = new Scanner(System.in);
        s.nextLine();
    }

    private GenericContainer<?> myContainer;

    private ContainerHelper() {
    }

    public synchronized void start(Properties properties) {
        if (myContainer == null) {
            String googleCreds   = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties);
            String polarisId     = POLARIS_ID.getValueFrom(properties);
            String polarisSecret = POLARIS_SECRET.getValueFrom(properties);
            myContainer = new GenericContainer<>(POLARIS_IMAGE_NAME)
                    .withCommand("sh", "-c", POLARIS_CMD)
                    .withEnv(ENVIRONMENT_VARIABLE, String.format("POLARIS,%s,%s", polarisId, polarisSecret))
                    // GCP
                    .withEnv("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_FILE)
                    .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
            POLARIS_PROPERTIES.forEach((x) -> {
              myContainer.withEnv(x.getPropertyName(), x.getValueFrom(properties));
            });
            Map.of(
                "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_CATALOG_API__LEVEL", "DEBUG",
                "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE__LEVEL", "DEBUG",
                "QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_EXCEPTION__LEVEL", "DEBUG",
                "QUARKUS_LOG_CATEGORY__IO_QUARKUS_HTTP_ACCESS-LOG__LEVEL", "DEBUG"
                  )
               .entrySet()
               .forEach(x -> myContainer.withEnv(x.getKey(), x.getValue()));

            myContainer.setPortBindings(List.of("8181:8181", "8182:8182", DEBUG_PORT + ":" + DEBUG_PORT));
            logger.info("Starting Polaris docker container. This usually takes about 10s, longer if it's downloading the image.");
            myContainer.start();
            myContainer.copyFileToContainer(Transferable.of(googleCreds.getBytes()), GOOGLE_FILE);
        }
    }

}
