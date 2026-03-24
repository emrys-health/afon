package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.ClusterPolarisConfiguration;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;

public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    interface ThrowableRunnable {
        void run() throws Exception;
    }

    static class Step {
        private final String            name;
        private final ThrowableRunnable function;

        Step(String name, ThrowableRunnable function) {
            this.name     = name;
            this.function = function;
        }
    }

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
        var          clusterConfig     = new ClusterPolarisConfiguration();
        ClusterState clusterState      = clusterConfig.getClusterState();
        log.info("Polaris credential: {}", clusterState.getCredentials());
    });
    public static Step waitFor(String domain) {
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

    private static final List<Step> steps
        = List.of(
        CLEAN_AWS,
        AWS_POLARIS,
        waitFor(AWS_POLARIS_DOMAIN_NAME),
        CLEAN_AZ,
        AZ_POLARIS,
        waitFor(AZURE_DOMAIN_NAME),
        AZ_POLARIS_FOREIGN_CATALOG
                 );

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
        pb.setWorkingDirectory(path.toFile())
          .setDestroyOnShutdown(true)
          .addStdOut(System.out)
          .addStdErr(System.err)
          .setConsoleBufferMaxLines(7000);
        ManagedProcess p = pb.build();
        p.start();
        p.isAlive();
        p.waitForExit();
        log.info("Command {} finished with value {}", command, p.exitValue());
    }

    public static void main(String[] args) {
        DeployExternalCatalogsMain app = new DeployExternalCatalogsMain();
        try {
            if (args.length == 0) {
                app.runSteps(steps);
            } else {
                String step  = args[0];
                int    index = steps.stream().map(s -> s.name).toList().lastIndexOf(step);
                if (index == -1) {
                    log.error("Step {} not found", step);
                } else {
                    app.runSteps(steps.subList(index, steps.size()));
                }
            }
        } catch (Exception e) {
            log.error("FAILED: " + e.getMessage(), e);
        }
    }

    private void runSteps(List<Step> steps) throws Exception {
        for (Step step : steps) {
            log.info("Running step {}", step.name);
            step.function.run();
        }
    }

}
