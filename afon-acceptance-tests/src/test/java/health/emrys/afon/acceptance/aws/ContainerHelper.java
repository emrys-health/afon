package health.emrys.afon.acceptance.aws;

import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import static health.emrys.afon.config.AfonProperties.*;
import static org.apache.polaris.core.persistence.bootstrap.RootCredentialsSet.ENVIRONMENT_VARIABLE;
import static org.slf4j.LoggerFactory.getLogger;

public class ContainerHelper {

    private static final Logger logger = getLogger(ContainerHelper.class);

    public static final ContainerHelper instance = new ContainerHelper();

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
            String polarisId = POLARIS_ID.getValueFrom(properties);
            String polarisSecret = POLARIS_SECRET.getValueFrom(properties);
            myContainer = new GenericContainer<>("apache/polaris")
//                    .withExposedPorts(8182)
                    .withEnv(AWS_ACCESS_KEY_ID.getPropertyName(), AWS_ACCESS_KEY_ID.getValueFrom(properties))
                    .withEnv(AWS_SECRET_ACCESS_KEY.getPropertyName(), AWS_SECRET_ACCESS_KEY.getValueFrom(properties))
                    .withEnv(POLARIS_ID.getPropertyName(), polarisId)
                    .withEnv(POLARIS_SECRET.getPropertyName(), polarisSecret)
                    .withEnv(ENVIRONMENT_VARIABLE, String.format("POLARIS,%s,%s", polarisId, polarisSecret))
//                    .withEnv("POLARIS_BOOTSTRAP_CREDENTIALS", String.format(""))
                    .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
//                    .waitingFor(Wait.forListeningPorts(8182))
//                    .waitingFor(Wait.forLogMessage("Apache Polaris Server", 1))
                    ;
            myContainer.setPortBindings(List.of("8181:8181", "8182:8182"));
//            myContainer.setPortBindings(List.of("8181:8181"));
            logger.info("Starting Polaris docker container. This can take about 10s...");
            myContainer.start();
        }
    }

}
