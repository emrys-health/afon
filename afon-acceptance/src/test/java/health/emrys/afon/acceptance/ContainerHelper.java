package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import health.emrys.afon.trino.KeyCloakTrinoServerConfiguration;
import health.emrys.afon.trino.TrinoServerConfiguration;
import org.apache.polaris.core.config.FeatureConfiguration;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.acceptance.AcceptanceTestConfig.testConfigIncludes;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
import static health.emrys.afon.polaris.keycloak.KeyCloakHelper.polarisOidcEnv;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.*;
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
        AfonPropertyProvider properties = AcceptanceTestConfig.instance.getProperties();
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

    public synchronized GenericContainer<?> startKeyCloak(AfonPropertyProvider properties) {
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
            new KeyCloakConfigurer(properties).config().entrySet().forEach(entry ->
                    keyCloak.withEnv(entry.getKey(), entry.getValue())
            );
            // Fixed binding so the host-side KeyCloakProvider can reach it at localhost:8080.
            keyCloak.setPortBindings(List.of(KEYCLOAK_PORT + ":" + KEYCLOAK_PORT));
            logger.info("Starting KeyCloak docker container. This usually takes about 20s, longer if " +
                            "it's downloading the image.");
            keyCloak.start();
            // Provision the realm, client and user/password that Polaris will trust.
            properties.setValue(OIDC_DOMAIN_NAME, KEYCLOAK_HOSTNAME);
            IdentityProvider identityProvider = new KeyCloakProvider(properties);
            identityProvider
                .registerPolarisUser(
                        properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME),
                        properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD));
            identityProvider
                .registerPolarisUser(
                        properties.getValue(USER_POLARIS_ID),
                        properties.getValue(USER_POLARIS_SECRET));
            identityProvider
                .registerPolarisUser(
                        properties.getValue(POLARIS_ID),
                        properties.getValue(POLARIS_SECRET));
        }
        return keyCloak;
    }

    public synchronized void startSparkConnect(AfonPropertyProvider properties) {
        if (sparkConnect == null) {
            sparkConnect = new GenericContainer<>(SPARK_IMAGE);
            SparkWarehouseConfig config = sparkFactory.createConfig(properties);
            AfonCatalogs afonCatalogs = new AfonCatalogs(properties);
            Stream<String> envs = afonCatalogs.all().stream().flatMap(
                catalog ->
                        config.sparkConfigs(catalog.toString()).entrySet().stream().map(
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

    public synchronized GenericContainer<?> startPolaris(AfonPropertyProvider properties) {
        if (polaris == null) {
            String polarisId = properties.getValue(POLARIS_ID);
            String polarisSecret = properties.getValue(POLARIS_SECRET);
            polaris = new GenericContainer<>(POLARIS_IMAGE_NAME)
                    // Same network as KeyCloak so Polaris can reach it via the KEYCLOAK_ALIAS host name.
                    .withNetwork(network)
                    // Declare the ports so the HTTP wait strategy can resolve them (the fixed bindings
                    // below pin the host ports to the same numbers).
                    .withExposedPorts(POLARIS_PORT, 8182, DEBUG_PORT)
                    .withCommand("sh", "-c", POLARIS_CMD)
                    .withNetworkAliases(POLARIS_NETWORK_HOST)
                    .withEnv("polaris.features." + FeatureConfiguration.ALLOW_OVERLAPPING_CATALOG_URLS.key(), "true") // you don't want this in prod but very useful for tests
                    .withEnv(ENVIRONMENT_VARIABLE, format("POLARIS,%s,%s", polarisId, polarisSecret))
                    // GCP
                    .withEnv("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_FILE)
                    .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200));
            getPolarisProperties().forEach((property, envName) -> {
                polaris.withEnv(envName, properties.getValue(property));
            });
            // Make Polaris authenticate against KeyCloak (OIDC) instead of its internal token store.
            polarisOidcEnv(
                    internalRealmUrl(KEYCLOAK_ALIAS, KEYCLOACK_PROTOCOL),
                    properties.getValue(USER_POLARIS_ID),
                    properties.getValue(USER_POLARIS_SECRET)).forEach(polaris::withEnv);
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
            if ( testConfigIncludes(GCP) ){
                polaris.copyFileToContainer(
                        Transferable.of(properties.getValue(GOOGLE_APPLICATION_CREDENTIALS).getBytes()),
                        GOOGLE_FILE);
            }
        }
        return polaris;
    }

    public synchronized void startTrino(AfonPropertyProvider properties) {
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
                AfonCatalogs afonCatalogs = new AfonCatalogs(properties);
                for (AfonCatalog catalog : Set.of(afonCatalogs.awsTrino(), afonCatalogs.aws(), afonCatalogs.gcp(), afonCatalogs.azure())) {
                    String containerDestination = format("%s/%s.properties", catalogPath, catalog);
                    logger.info("Copying config file to container: {}", containerDestination);
                    trino.copyFileToContainer(
                        Transferable.of(trinoLocalConfig(properties, catalog.toString()).getBytes()),
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
        return new FileFinder().fullPathOf("scripts/trino/config.properties");
    }

    public static String trinoLocalConfig(
        AfonPropertyProvider properties,
        String catalog) {
        String polarisDomainName = "http://" + POLARIS_NETWORK_HOST + ":" + POLARIS_PORT;
        String keyCloakUrl       = tokenEndpoint(KEYCLOAK_ALIAS, KEYCLOACK_PROTOCOL);
        logger.info("Trino sees Polaris as {} and KeyCloak as {}", polarisDomainName, keyCloakUrl);
        TrinoServerConfiguration trinoConfig = new KeyCloakTrinoServerConfiguration(
                properties, polarisDomainName,
                // Trino runs in host network mode, so it reaches KeyCloak on the host at localhost:8080.
            keyCloakUrl
        );
        return trinoConfig.catalogProperties(catalog);
    }

}
