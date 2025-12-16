package health.emrys.afon.acceptance.aws;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.PolarisConstants;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_CMD;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_IMAGE_NAME;
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
                    // AWS
                    .withEnv(AWS_ACCESS_KEY_ID.getPropertyName(), AWS_ACCESS_KEY_ID.getValueFrom(properties))
                    .withEnv(AWS_SECRET_ACCESS_KEY.getPropertyName(), AWS_SECRET_ACCESS_KEY.getValueFrom(properties))
                    // Azure
                    .withEnv(AZURE_CLIENT_SECRET.getPropertyName(), AZURE_CLIENT_SECRET.getValueFrom(properties))
                    .withEnv(AZURE_CLIENT_ID.getPropertyName(), AZURE_CLIENT_ID.getValueFrom(properties))
                    .withEnv(AZURE_TENANT_ID.getPropertyName(), AZURE_TENANT_ID.getValueFrom(properties))
                    // Polaris
                    .withEnv(POLARIS_ID.getPropertyName(), polarisId)
                    .withEnv(POLARIS_SECRET.getPropertyName(), polarisSecret)
                    .withEnv(ENVIRONMENT_VARIABLE, String.format("POLARIS,%s,%s", polarisId, polarisSecret))
                    // GCP
                    .withEnv("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_FILE)
                    .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
            ;
            myContainer.setPortBindings(List.of("8181:8181", "8182:8182"));
            logger.info("Starting Polaris docker container. This usually takes about 10s, longer if it's downloading the image.");
            myContainer.start();
            myContainer.copyFileToContainer(Transferable.of(googleCreds.getBytes()), GOOGLE_FILE);
        }
    }

}
