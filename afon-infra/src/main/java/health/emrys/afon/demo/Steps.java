package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.cdk.PrometheusClusterDeploy;
import health.emrys.afon.cdk.SparkClusterDeployment;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.LocalPolarisConfiguration;
import health.emrys.afon.infra.checks.SparkCheck;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.commons.io.file.PathUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE_EXTERNAL;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.getDeleteNamespaceURI;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.getDeleteTableURI;

public class Steps {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Steps.class);

    public static final String EXTERNAL_NAMESPACE = "namespaceph";

    public static final Step CLEAN_AWS   = new Step("clean_aws", () -> {
        deleteStack(AwsWholeStack.ID);
    });

    public static final Step AWS_POLARIS = new Step("aws_polaris", () -> {
        AwsWholeStack.main(new String[]{});
        startCloud(cdktfPath(AwsWholeStack.ID));
    });

    public static final Step AWS_SPARK = new Step("aws_spark", () -> {
        SparkClusterDeployment.main(new String[]{});
        startCloud(cdktfPath(SparkClusterDeployment.ID));
    });

    public static final Step AWS_PROMETHEUS = new Step("aws_prometheus", () -> {
        PrometheusClusterDeploy.main(new String[]{});
        startCloud(cdktfPath(PrometheusClusterDeploy.ID));
    });

    public static final Step CLEAN_AZ    = new Step("clean_az", () -> {
        deleteStack(AzureKubernetesPolaris.ID);
    });

    public static final Step AZ_POLARIS  = new Step("az_polaris", () -> {
        AzureKubernetesPolaris.main(new String[]{});
        startCloud(cdktfPath(AzureKubernetesPolaris.ID));
    });

    public static final Step AZ_POLARIS_CLEAN_FOREIGN_CATALOG = new Step("az_foreign_clean", () -> {
        var client = new AfonPolarisClient(AZURE_DOMAIN_NAME, appProperties());
        String accessToken = client.getAccessToken();
        client.delete(getDeleteTableURI("azure", EXTERNAL_NAMESPACE, SparkCheck.TABLE), accessToken);
        client.delete(getDeleteNamespaceURI("azure", EXTERNAL_NAMESPACE), accessToken);
    });

    public static final Step AZ_POLARIS_FOREIGN_CATALOG = new Step("az_foreign", () -> {
        LocalPolarisConfiguration config     = new LocalPolarisConfiguration(AZURE_DOMAIN_NAME);
        Properties                properties = appProperties();
        // swap the buckets
        properties.setProperty(AZURE_BUCKET.getPropertyName(), AZURE_EXTERNAL_BUCKET.getValueFrom(properties));
        checkResponses(config.initialize(AZURE, properties));
    });

    private static Properties appProperties() {
        return new AfonPropertiesLoader().getProperties();
    }

    public static final Step AZ_POLARIS_EXTERNAL_AWS = new Step("az_external_aws", () -> {
        LocalPolarisConfiguration config     = new LocalPolarisConfiguration(AZURE_DOMAIN_NAME);
        checkResponses(config.initialize(AZURE_EXTERNAL));
    });

    public static final Step AWS_ALL_CATALOGS = new Step("aws_all_catalogs", () -> {
        LocalPolarisConfiguration config     = new LocalPolarisConfiguration(AWS_POLARIS_DOMAIN_NAME);
        checkResponses(config.initializePolaris());
    });

    public static final Step WAIT_FOR_AWS_POLARIS = Steps.waitFor(AWS_POLARIS_DOMAIN_NAME);

    public static final Step WAIT_FOR_AZ_POLARIS  = Steps.waitFor(AZURE_DOMAIN_NAME);

    public static final Step CHECK_AWS_TO_AZURE_EXTERNAL = new Step("az_external_aws_check", () -> {
        Properties properties = appProperties();
        properties.put(POLARIS_ENDPOINT.getPropertyName(), AZURE_DOMAIN_NAME);
        SparkCheck sparkCheck = new SparkCheck();
        sparkCheck.talkTo(sparkCheck.sparkSessionUsing(AWS_POLARIS_DOMAIN_NAME,
                                                       POLARIS_ID.getValueFrom(properties),
                                                       POLARIS_SECRET.getValueFrom(properties),
                                                       AZURE_EXTERNAL.toString()),
                          AZURE_EXTERNAL.toString(),
                          EXTERNAL_NAMESPACE);
    });

    private static void checkResponses(List<HttpResponse> responses) {
        log.info("HTTP responses = {}", responses);
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
            if (!badResponses.isEmpty()) {
                throw new RuntimeException(badResponses.toString());
            }
        }
    }
    private static void useAwsKubernetes() throws ManagedProcessException {
        run("kubectl", null, "config", "use-context", "arn:aws:eks:eu-west-2:123456789012:cluster/spark-cluster");
    }
    private static void useAzureKubernetes() throws ManagedProcessException {
        run("kubectl", null, "config", "use-context", "spark-cluster");
    }

    private static Step waitFor(String domain) {
        return new Step("waitFor_" + domain.substring(0, domain.indexOf('.')), () -> {
            boolean stopResolving   = false;
            long    start           = System.currentTimeMillis();
            int     timeout         = 900_000;
            log.info("Checking DNS for {}", domain);
            while (!stopResolving && System.currentTimeMillis() - start < timeout) {
                try {
                    InetAddress[] addresses = InetAddress.getAllByName(domain);
                    stopResolving = addresses != null && addresses.length > 0;
                    if (!stopResolving) {
                        stopResolving = isWaitingInterrupted(timeout);
                    }
                } catch (UnknownHostException e) {
                    stopResolving = isWaitingInterrupted(timeout);
                }
            }
        });
    }

    private static boolean isWaitingInterrupted(int timeout) {
        int pause = 5000;
        log.info("Waiting {}s. Timeout = ", pause/1000, timeout /1000);
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            return true;
        }
        return false;
    }

    private static @NonNull Path deleteStack(SupportedCloudsEnum id) throws IOException {
        Path toDelete = cdktfPath(id);
        log.info("Deleting {}", toDelete);
        PathUtils.delete(toDelete);
        return toDelete;
    }

    private static @NonNull Path cdktfPath(SupportedCloudsEnum id) {
        return Path.of("cdktf.out", "stacks", id.toString());
    }

    private static void startCloud(Path jsonDirectory) throws ManagedProcessException {
        run("tofu", jsonDirectory, "init");
        run("tofu", jsonDirectory, "apply", "--auto-approve");
    }

    private static void run(String command, Path path, String... commands) throws ManagedProcessException {
        ManagedProcessBuilder pb = new ManagedProcessBuilder(command);
        for (String cmd : commands) {
            pb.addArgument(cmd);
        }
        if (path != null) {
            pb.setWorkingDirectory(path.toFile());
        }
        pb.setDestroyOnShutdown(true)
          .addStdOut(System.out)
          .addStdErr(System.err)
          .setConsoleBufferMaxLines(7000);
        ManagedProcess p = pb.build();
        p.start();
        p.isAlive();
        p.waitForExit();
        log.info("Command {} finished with value {}", command, p.exitValue());
    }

}
