package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsConstruct;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.ClusterPolarisConfiguration;
import health.emrys.afon.infra.checks.SparkCheck;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.apache.commons.io.file.PathUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE_EXTERNAL;

public class Steps {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Steps.class);

    public static final Step CLEAN_AWS   = new Step("clean_aws", () -> {
        deleteStack(AwsWholeStack.ID);
    });

    public static final Step AWS_POLARIS = new Step("aws_polaris", () -> {
        AwsWholeStack.main(new String[]{});
        startCloud(cdktfPath(AwsWholeStack.ID));
    });

    public static final Step CLEAN_AZ    = new Step("clean_az", () -> {
        deleteStack(AzureKubernetesPolaris.ID);
    });

    public static final Step AZ_POLARIS  = new Step("az_polaris", () -> {
        AzureKubernetesPolaris.main(new String[]{});
        startCloud(cdktfPath(AzureKubernetesPolaris.ID));
    });

    public static final Step AZ_POLARIS_FOREIGN_CATALOG = new Step("az_foreign", () -> {
        useAzureKubernetes();
        ClusterPolarisConfiguration config     = new ClusterPolarisConfiguration();
        Properties                  properties = new AfonPropertiesLoader().getProperties();
        // swap the buckets
        properties.setProperty(AZURE_BUCKET.getPropertyName(), AZURE_EXTERNAL_BUCKET.getValueFrom(properties));
        config.initialize(AZURE, properties);
    });

    public static final Step AZ_POLARIS_EXTERNAL_AWS = new Step("az_external_aws", () -> {
        useAzureKubernetes();
        Properties                  properties = propertiesForCurrentCluster();
        useAwsKubernetes();
        ClusterPolarisConfiguration config     = new ClusterPolarisConfiguration();
        config.initialize(AZURE_EXTERNAL, properties);
    });

    private static @NonNull Properties propertiesForCurrentCluster() throws IncorrectConfigException, IOException, ApiException {
        var          clusterConfig = new ClusterPolarisConfiguration();
        ClusterState clusterState  = clusterConfig.getClusterState();
        String       credential    = clusterState.getCredentials();
        Properties   properties    = AfonPropertiesLoader.add(credential, new AfonPropertiesLoader().getProperties());
        return properties;
    }

    public static final Step WAIT_FOR_AWS_POLARIS = Steps.waitFor(AWS_POLARIS_DOMAIN_NAME);

    public static final Step WAIT_FOR_AZ_POLARIS  = Steps.waitFor(AZURE_DOMAIN_NAME);

    public static final Step CHECK_AWS_TO_AZURE_EXTERNAL = new Step("az_external_aws_check", () -> {
        useAwsKubernetes();
        Properties properties = propertiesForCurrentCluster();
        SparkCheck sparkCheck = new SparkCheck();
        sparkCheck.talkTo(sparkCheck.sparkSessionUsing(AWS_POLARIS_DOMAIN_NAME,
                                                       POLARIS_ID.getValueFrom(properties),
                                                       POLARIS_SECRET.getValueFrom(properties),
                                                       AZURE_EXTERNAL.toString()),
                          AZURE_EXTERNAL.toString(),
                          "namespaceph");
    });

    private void checkResponses(List<HttpResponse> responses) {
        List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
        if (!badResponses.isEmpty()) {
            throw new RuntimeException(badResponses.toString());
        }
    }
    private static void useAwsKubernetes() throws ManagedProcessException {
        run("kubectl", null, "config", "use-context", "arn:aws:eks:eu-west-2:418295715997:cluster/spark-cluster");
    }
    private static void useAzureKubernetes() throws ManagedProcessException {
        run("kubectl", null, "config", "use-context", "spark-cluster");
    }

    private static Step waitFor(String domain) {
        return new Step("waitFor" + domain.substring(domain.indexOf('.')), () -> {
            boolean resolved    = false;
            long    start       = System.currentTimeMillis();
            int     timeout     = 900_000;
            while (!resolved && System.currentTimeMillis() - start < timeout) {
                try {
                    InetAddress[] addresses = InetAddress.getAllByName(domain);
                    resolved = addresses != null && addresses.length > 0;
                    if (!resolved) {
                        int pause = 1000;
                        log.info("Waiting {}s for cloud state to be resolved. Timeout = ", pause/1000, timeout/1000);
                        Thread.sleep(pause);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static @NonNull Path deleteStack(String id) throws IOException {
        Path toDelete = cdktfPath(id);
        log.info("Deleting {}", toDelete);
        PathUtils.delete(toDelete);
        return toDelete;
    }

    private static @NonNull Path cdktfPath(String id) {
        return Path.of("cdktf.out", "stacks", id);
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
