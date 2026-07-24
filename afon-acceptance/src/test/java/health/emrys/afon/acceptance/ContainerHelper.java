package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.security.keycloak.KeyCloakAdmin;
import health.emrys.afon.security.keycloak.KeyCloakConfig;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import health.emrys.afon.trino.KeyCloakTrinoServerConfiguration;
import health.emrys.afon.trino.TrinoServerConfiguration;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
import static health.emrys.afon.polaris.keycloak.KeyCloakHelper.polarisOidcEnv;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.*;
import static health.emrys.afon.spark.SparkWarehouseConfig.SPARK_IMAGE;
import static java.lang.String.format;
import static org.apache.polaris.core.persistence.bootstrap.RootCredentialsSet.ENVIRONMENT_VARIABLE;
import static org.slf4j.LoggerFactory.getLogger;

public class ContainerHelper {

    private static final Logger logger = getLogger(ContainerHelper.class);

    public static final ContainerHelper instance = new ContainerHelper();

    private static final String GOOGLE_FILE = "/tmp/application_default_credentials.json";

    /**
     * Network alias used by Polaris (running on the same docker network) to reach KeyCloak.
     */
    private static final String KEYCLOAK_ALIAS       = "keycloak";
    public static final  String KEYCLOAK_HOSTNAME    = "localhost";
    public static final  String KEYCLOACK_PROTOCOL   = "http";
    public static final  String POLARIS_NETWORK_HOST = "polaris-service";
    public static final  int    POLARIS_PORT         = 8181;

    /**
     * For testing.
     */
    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        // KeyCloak must be up (and the Polaris realm provisioned) before Polaris starts, as Polaris
        // resolves its OIDC configuration from KeyCloak.
        instance.startKeyCloak(properties);
        instance.startPolaris(properties);
        instance.startTrino(properties);
        instance.startSparkConnect(properties);
        System.out.println("Press enter to continue...");
        Scanner s = new Scanner(System.in);
        s.nextLine();
        logger.info("That's all folks!");
    }

    private final Network                     network      = Network.newNetwork();
    private final SparkWarehouseConfigFactory sparkFactory = SparkWarehouseConfigFactory.getFactory();

    private GenericContainer<?> polaris;
    private GenericContainer<?> trino;
    private GenericContainer<?> sparkConnect;
    private GenericContainer<?> keyCloak;

    private ContainerHelper() {
    }

    public synchronized GenericContainer<?> startKeyCloak(Properties properties) {
        if (keyCloak == null) {
            keyCloak = new GenericContainer<>(KEYCLOAK_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(KEYCLOAK_ALIAS)
                // Declare the port so the HTTP wait strategy can resolve it (the fixed binding below
                // pins the host port to the same number).
                .withExposedPorts(KEYCLOAK_PORT)
                .withCommand("start-dev")
                .waitingFor(Wait.forHttp("/realms/master")
                                .forPort(KEYCLOAK_PORT)
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)));
            KeyCloakConfig kcConfig = new KeyCloakConfig(properties);
            kcConfig.configUsing().entrySet().forEach(entry ->
                                                          keyCloak.withEnv(entry.getKey(), entry.getValue())
            );
            // Fixed binding so the host-side KeyCloakAdmin can reach it at localhost:8080.
            keyCloak.setPortBindings(List.of(KEYCLOAK_PORT + ":" + KEYCLOAK_PORT));
            logger.info("Starting KeyCloak docker container. This usually takes about 20s, longer if " +
                            "it's downloading the image.");
            keyCloak.start();
            // Provision the realm, client and user/password that Polaris will trust.
            KeyCloakAdmin keyCloakAdmin = new KeyCloakAdmin(properties, KEYCLOAK_HOSTNAME);
            keyCloakAdmin
                .registerPolarisUser(
                    KC_BOOTSTRAP_ADMIN_USERNAME.getValueFrom(properties),
                    KC_BOOTSTRAP_ADMIN_PASSWORD.getValueFrom(properties));
            keyCloakAdmin
                .registerPolarisUser(
                    USER_POLARIS_ID.getValueFrom(properties),
                    USER_POLARIS_SECRET.getValueFrom(properties));
            keyCloakAdmin
                .registerPolarisUser(
                    POLARIS_ID.getValueFrom(properties),
                    POLARIS_SECRET.getValueFrom(properties));
        }
        return keyCloak;
    }

    public synchronized void startSparkConnect(Properties properties) {
        if (sparkConnect == null) {
            sparkConnect = new GenericContainer<>(SPARK_IMAGE);
            SparkWarehouseConfig config = sparkFactory.getConfig(properties);
            Stream<String> envs = Arrays.stream(values()).flatMap(
                cloudProvider ->
                    config.sparkConfigs(cloudProvider).entrySet().stream().map(
                        entry -> format("-D%s=%s", entry.getKey(), entry.getValue()))
            );
            String logging = "-Dlog4j2.logger.grpc.name=io.grpc -Dlog4j2.logger.grpc.level=DEBUG -Dlog4j2.logger" +
                ".netty.name=io.grpc.netty -Dlog4j2.logger.netty.level=TRACE";
            String envString = envs.collect(Collectors.joining(" "))
                + " -Dspark.master=local[4]"
                + " -Dspark.connect.grpc.interceptor.classes=org.apache.spark.sql.connect.service" +
                ".LoggingInterceptor"
                + " " + logging;
            sparkConnect.withEnv("spark.driver.extraJavaOptions", logging);
            sparkConnect.withEnv("LOG4J_LOGGER_IO_GRPC", "DEBUG");
            sparkConnect.withEnv("LOG4J_LOGGER_ORG_APACHE_SPARK_SQL_CONNECT", "DEBUG");
            sparkConnect.withEnv("LOG4J_LOGGER_IO_GRPC_NETTY", "TRACE");
            logger.info("Spark connect env: {}", envString);

            sparkConnect.withEnv("SPARK_SUBMIT_OPTS", envString);
            sparkConnect.setNetworkMode("host");
            sparkConnect.start();
        }
    }

    public synchronized GenericContainer<?> startPolaris(Properties properties) {
        if (polaris == null) {
            String googleCreds   = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties);
            String polarisId     = POLARIS_ID.getValueFrom(properties);
            String polarisSecret = POLARIS_SECRET.getValueFrom(properties);
            polaris = new GenericContainer<>(POLARIS_IMAGE_NAME)
                // Same network as KeyCloak so Polaris can reach it via the KEYCLOAK_ALIAS host name.
                .withNetwork(network)
                // Declare the ports so the HTTP wait strategy can resolve them (the fixed bindings
                // below pin the host ports to the same numbers).
                .withExposedPorts(POLARIS_PORT, 8182, DEBUG_PORT)
                .withCommand("sh", "-c", POLARIS_CMD)
                .withNetworkAliases(POLARIS_NETWORK_HOST)
                .withEnv(ENVIRONMENT_VARIABLE, format("POLARIS,%s,%s", polarisId, polarisSecret))
                // GCP
                .withEnv("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_FILE)
                .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
            getPolarisProperties().forEach((x) -> {
                polaris.withEnv(x.getPropertyName(), x.getValueFrom(properties));
            });
            // Make Polaris authenticate against KeyCloak (OIDC) instead of its internal token store.
            polarisOidcEnv(
                internalRealmUrl(KEYCLOAK_ALIAS, KEYCLOACK_PROTOCOL),
                USER_POLARIS_ID.getValueFrom(properties),
                USER_POLARIS_SECRET.getValueFrom(properties)).forEach(polaris::withEnv);
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
        return polaris;
    }

    public synchronized void startTrino(Properties properties) {
        if (trino == null) {
            try {
                trino = new GenericContainer<>("trinodb/trino@sha256:5b5e0a97f599a30a2e23c7c7061e4e4d546a914b4fad2a2a002998140ceb4006");
                String catalogPath    = "/etc/trino/catalog";
                String propertiesPath = trinoConfigPropertiesFile().getAbsolutePath();
                logger.info("About to mount {}", propertiesPath);
                trino.withFileSystemBind(propertiesPath, "/etc/trino/config.properties");
                trino.setPortBindings(List.of("8090:8090"));
                trino.withNetwork(network);
//                trino.withLogConsumer(new Slf4jLogConsumer(logger));
                trino.start();
                for (SupportedCloudsEnum catalog : Set.of(AWS_TRINO, AWS, GCP, AZURE)) {
                    String containerDestination = format("%s/%s.properties", catalogPath, catalog);
                    logger.info("Copying config file to container: {}", containerDestination);
                    trino.copyFileToContainer(
                        Transferable.of(trinoLocalConfig(properties, catalog).getBytes()),
                        containerDestination);
                }
                logger.info("Started Trino with container ID: {}", trino.getContainerId());
            } catch (Exception e) {
                throw new RuntimeException("Cannot start Trino container", e);
            }
        }
    }

    public static Properties trinoConfigProperties() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(trinoConfigPropertiesFile().toPath())) {
            props.load(reader);
        }
        return props;
    }

    private static File trinoConfigPropertiesFile() throws IOException {
        FileFinder fileFinder = new FileFinder();
        File       config     = fileFinder.fullPathOf("scripts/trino/config.properties");
        return config;
    }

    public static String trinoLocalConfig(
        Properties properties,
        SupportedCloudsEnum catalog) {
        String polarisDomainName = "http://" + POLARIS_NETWORK_HOST + ":" + POLARIS_PORT;
        String keyCloakUrl       = tokenEndpoint(KEYCLOAK_ALIAS, KEYCLOACK_PROTOCOL);
        logger.info("Trino sees Polaris as {} and KeyCloak as {}", polarisDomainName, keyCloakUrl);
        TrinoServerConfiguration trinoConfig = new KeyCloakTrinoServerConfiguration(
            polarisDomainName,
            properties,
            // Trino runs in host network mode, so it reaches KeyCloak on the host at localhost:8080.
            keyCloakUrl
        );
        return trinoConfig.catalogProperties(catalog);
    }

}
