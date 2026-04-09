package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.EndpointPolarisConfiguration;
import health.emrys.afon.infra.checks.SparkCheck;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.commons.io.file.PathUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE_EXTERNAL;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.getDeleteNamespaceURI;
import static health.emrys.afon.polaris.comms.PolarisConfigurer.getDeleteTableURI;

public class StepActions {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(StepActions.class);

    public Step waitFor(String domain) {
        return new Step("waitFor_" + domain.substring(0, domain.indexOf('.')),
                        "wait for internet DNS servers to recognise " + domain,
                        () -> {
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

    private boolean isWaitingInterrupted(int timeout) {
        int pause = 5000;
        log.info("Waiting {}s. Maximum = {}s", pause/1000, timeout /1000);
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
            log.info("Stopping {} if it's running already...", toDelete);
            run("tofu", toDelete, "destroy", "--auto-approve");

            log.info("Deleting {}", toDelete);
            PathUtils.delete(toDelete);
        } else {
            log.info("Directory {} does not exist so starting afresh", toDelete);
        }
        return toDelete;
    }

    public @NonNull Path cdktfPath(SupportedCloudsEnum id) {
        return Path.of("cdktf.out", "stacks", id.toString());
    }

    public void startCloud(Path jsonDirectory) throws ManagedProcessException {
        run("tofu", jsonDirectory, "init");
        run("tofu", jsonDirectory, "apply", "--auto-approve");
    }

    public void clean(String domainName, String namespace, String table) throws IOException {
        var    client      = new AfonPolarisClient(domainName, new AfonPropertiesLoader().getProperties());
        String accessToken = client.getAccessToken();
        client.delete(getDeleteTableURI("azure", namespace, table), accessToken);
        client.delete(getDeleteNamespaceURI("azure", namespace), accessToken);
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

    public void deleteTerraformIn(String directory) throws IOException {
        Files.list(Path.of(directory)).filter(x -> x.getFileName().toString().startsWith("terraform.")).forEach(
            x -> {
                delete(x);
            });
    }

    public void delete(Path x) {
        log.info("Deleting {}", x);
        try {
            Files.deleteIfExists(x);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog,
                                        Properties properties) throws IncorrectConfigException, IOException {
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        checkResponses(config.initialize(catalog, properties));
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog) throws IncorrectConfigException, IOException {
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        checkResponses(config.initialize(catalog));
    }

    public void createAllCatalogsFor(String domainName) throws IncorrectConfigException, IOException {
        EndpointPolarisConfiguration config = new EndpointPolarisConfiguration(domainName);
        checkResponses(config.initializePolaris());
    }

    private void checkResponses(List<HttpResponse> responses) {
        log.info("HTTP responses = {}", responses);
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
            if (!badResponses.isEmpty()) {
                throw new RuntimeException(badResponses.toString());
            }
        }
    }

}
