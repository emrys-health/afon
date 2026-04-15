package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.cdk.GcpKubernetesPolaris;
import health.emrys.afon.cdk.SparkClusterDeployment;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.infra.EndpointPolarisConfiguration;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static org.slf4j.LoggerFactory.getLogger;

public class StepActions {

    private static final Logger log = getLogger(StepActions.class);
    public static final String TERRAFORM_STATE_FILE_PATTERN = "terraform.";

    public Step waitFor(String domain) {
        return new Step("waitFor_" + domain.substring(0, domain.indexOf('.')),
                        "wait for internet DNS servers to recognise " + domain,
                        () -> {
                            boolean stopResolving = false;
                            long    start         = System.currentTimeMillis();
                            int     timeout       = 1_200_000;
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

    private boolean isWaitingInterrupted(int timeout) {
        int pause = 5000;
        log.info("Waiting {}s. Maximum = {}s", pause / 1000, timeout / 1000);
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            return true;
        }
        return false;
    }

    public @NonNull Path deleteStack(SupportedCloudsEnum id) throws IOException {
        Path toDelete = cdktfPath(id);
        if (toDelete.toFile().exists()) {
            attemptGracefulDestroy(toDelete);
            log.info("Deleting {}", toDelete);
            delete(toDelete);
        } else {
            log.info("Directory {} does not exist so starting afresh", toDelete);
        }
        deleteTerraformStateFiles(id);
        return toDelete;
    }

    private void attemptGracefulDestroy(Path path) {
        try {
            log.info("Stopping {} if it's running already...", path);
            run("tofu", path, "init");
            run("tofu", path, "destroy", "--auto-approve");
        } catch (Exception e) {
            log.error("Failed to call tofu destroy in " + path, e);
        }
    }

    public @NonNull Path cdktfPath(SupportedCloudsEnum id) {
        return Path.of("cdktf.out", "stacks", id.toString());
    }

    public void startGcpPolaris() throws Exception {
        executeMainOf(GcpKubernetesPolaris.class, new String[]{});
        startCloud(cdktfPath(GcpKubernetesPolaris.ID));
    }

    public void startAzurePolaris() throws Exception {
        executeMainOf(AzureKubernetesPolaris.class, new String[]{});
        startCloud(cdktfPath(AzureKubernetesPolaris.ID));
    }

    public void startSparkClusterInAws() throws Exception {
        executeMainOf(SparkClusterDeployment.class, new String[]{});
        startCloud(cdktfPath(SparkClusterDeployment.ID));
    }

    public void startAwsPolaris() throws Exception {
        executeMainOf(AwsWholeStack.class, new String[]{});
        startCloud(cdktfPath(AwsWholeStack.ID));
    }

    private void executeMainOf(Class clazz, String[] args) throws Exception {
        executeMainOf(clazz, args, new Properties());
    }

    public void executeMainOf(Class clazz, String[] args, Properties overrides) throws Exception {
        log.info("Running {} with arguments {} and overrides {}",
                 clazz.getName(),
                 String.join(" ", args),
                 overrides);

        Properties original = saveAndSetSystem(overrides);
        try {
            Method main = clazz.getDeclaredMethod("main", String[].class);
            main.invoke(null, (Object)args);
        } finally {
            restoreSystemProperties(overrides, original);
        }
    }

    public void startCloud(Path jsonDirectory) throws ManagedProcessException {
        run("tofu", jsonDirectory, "init");
        run("tofu", jsonDirectory, "apply", "--auto-approve");
    }

    public void run(String command, Path path, String... commands) throws ManagedProcessException {
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

    public void deleteTerraformStateFiles(SupportedCloudsEnum id) throws IOException {
        deleteTerraformIn(".", id);
        deleteTerraformIn("..", id);
    }
    
    private void deleteTerraformIn(String directory,
                                   SupportedCloudsEnum id) throws IOException {
        Files.list(Path.of(directory))
             .filter(x -> {
                 String fileName = x.getFileName().toString();
                 return fileName.startsWith(TERRAFORM_STATE_FILE_PATTERN + id)
                     && fileName.endsWith(".tfstate");
             })
             .forEach(x -> {
                delete(x);
            });
    }

    public void delete(Path x) {
        log.info("Deleting {}", x);
        try {
            PathUtils.delete(x);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog,
                                        Properties properties) throws Exception {
        properties.put(POLARIS_ENDPOINT.getPropertyName(), domainName);
        executeMainOf(EndpointPolarisConfiguration.class,
                      new String[] {catalog.name()},
                      properties);
    }

    private static void restoreSystemProperties(Properties properties, Properties original) {
        properties.keySet().forEach(propKey -> {
            String key   = propKey.toString();
            String value = original.getProperty(key);
            log.info("Restoring system property {} = {}", key, value);
            if (value != null) {
                System.setProperty(key, value);
            }  else {
                System.clearProperty(key);
            }
        });
    }

    private static @NonNull Properties saveAndSetSystem(Properties properties) {
        Properties original = new Properties();
        properties.keySet().forEach(mapKey -> {
            String key          = mapKey.toString();
            String systemProp   = System.getProperty(key);
            if (systemProp != null) {
                original.put(key, systemProp);
            }
            String value = properties.getProperty(key);
            log.info("Setting system property {} = {}", key, value);
            System.setProperty(key, value);
        });
        return original;
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog) throws Exception {
        createSingleCatalogsFor(domainName, catalog, new Properties());
    }

}
