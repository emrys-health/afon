package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.trino.TrinoServerConfiguration;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
import static health.emrys.afon.spark.SparkWarehouseConfig.SPARK_IMAGE;
import static java.lang.String.format;
import static org.apache.polaris.core.persistence.bootstrap.RootCredentialsSet.ENVIRONMENT_VARIABLE;
import static org.slf4j.LoggerFactory.getLogger;

public class ContainerHelper {

    private static final Logger logger = getLogger(ContainerHelper.class);

    public static final ContainerHelper instance = new ContainerHelper();

    private static final String GOOGLE_FILE = "/tmp/application_default_credentials.json";

    /**
     * For testing.
     */
    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        instance.startPolaris(properties);
        instance.startSparkConnect(properties);
        System.out.println("Press enter to continue...");
        Scanner s = new Scanner(System.in);
        s.nextLine();
        logger.info("That's all folks!");
    }

    private GenericContainer<?> polaris;
    private GenericContainer<?> trino;
    private GenericContainer<?> sparkConnect;

    private ContainerHelper() {
    }

    public synchronized void startSparkConnect(Properties properties) {
        if (sparkConnect == null) {
            sparkConnect = new GenericContainer<>("ph1ll1phenry/spark_jars:4.0.1");
            SparkWarehouseConfig config = new SparkWarehouseConfig(properties);
            Stream<String> envs = Arrays.stream(values()).flatMap(
                cloudProvider ->
                    config.sparkConfigs(cloudProvider).entrySet().stream().map(
                        entry -> format("-D%s=%s", entry.getKey(), entry.getValue()))
                                                                         );
            String logging = "-Dlog4j2.logger.grpc.name=io.grpc -Dlog4j2.logger.grpc.level=DEBUG -Dlog4j2.logger.netty.name=io.grpc.netty -Dlog4j2.logger.netty.level=TRACE";
            String envString = envs.collect(Collectors.joining(" "))
                 + " -Dspark.master=local[4]"
                 + " -Dspark.connect.grpc.interceptor.classes=org.apache.spark.sql.connect.service.LoggingInterceptor"
                 + " " + logging;
            sparkConnect.withEnv("spark.driver.extraJavaOptions", logging);
            sparkConnect.withEnv("LOG4J_LOGGER_IO_GRPC", "DEBUG");
            sparkConnect.withEnv("LOG4J_LOGGER_ORG_APACHE_SPARK_SQL_CONNECT", "DEBUG");
            sparkConnect.withEnv("LOG4J_LOGGER_IO_GRPC_NETTY", "TRACE");
            logger.info("Spark connect env: {}", envString);

            sparkConnect.withEnv("SPARK_SUBMIT_OPTS", envString);
//            sparkConnect.setPortBindings(List.of("15002:15002"));
            sparkConnect.setNetworkMode("host");
            sparkConnect.start();
        }
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
                File       config      = fileFinder.fullPathOf("scripts/trino/config.properties");
                String     catalogPath = "/etc/trino/catalog";
                trino.withFileSystemBind(config.getAbsolutePath(), "/etc/trino/config.properties");
                trino.setNetworkMode("host");
                trino.start();
                for (SupportedCloudsEnum catalog : Set.of(AWS_TRINO, AWS, GCP, AZURE)) {
                    trino.copyFileToContainer(
                        Transferable.of(trinoLocalConfig(properties, catalog).getBytes()),
                        format("%s/%s.properties", catalogPath, catalog));
                }
            } catch (Exception e) {
                throw new RuntimeException("Cannot start Trino container", e);
            }
        }
    }

    public static String trinoLocalConfig(Properties properties,
                                          SupportedCloudsEnum catalog) {
        TrinoServerConfiguration trinoConfig = new TrinoServerConfiguration(
            POLARIS_ENDPOINT.getValueFrom(properties),
            properties
        );
        return trinoConfig.catalogProperties(catalog);
    }

}
