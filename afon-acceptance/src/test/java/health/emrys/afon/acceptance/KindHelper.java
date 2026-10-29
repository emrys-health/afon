package health.emrys.afon.acceptance;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static health.emrys.afon.cdk.DockerImages.REGISTRY_IMAGE;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * A throwaway <a href="https://kind.sigs.k8s.io">kind</a> cluster (a Kubernetes cluster in Docker) with a local registry and Kyverno, for the
 * tests of the signing of images.  It drives the {@code docker}, {@code kind}, {@code kubectl}, {@code helm} and {@code cosign}
 * commands, which must be installed, and never touches the user's kubeconfig, using one of its own.
 * <p>
 * The registry is a Testcontainers container, joined to kind's Docker network (see {@link #connectRegistry()}) under an alias of its own, so that the
 * Kyverno of the cluster reaches it by that name, while the tests reach it at {@code localhost:<port>}: both see the same repositories,
 * and a signature is found by the digest of the image whatever the name of the registry.
 */
public class KindHelper implements AutoCloseable {

    private static final Logger logger = getLogger(KindHelper.class);

    public static final List<String> TOOLS = List.of("docker", "kind", "kubectl", "helm", "cosign");
    public static final int REGISTRY_PORT = 5000;
    private static final String KIND_NETWORK = "kind";
    private static final String KYVERNO_CHART = "kyverno-3.9.2.tgz";

    /**
     * The result of a command.
     */
    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final Path directory;
    private final String clusterName = "afon-signing-" + UUID.randomUUID().toString().substring(0, 8);
    // Not shared with any other run or registry: the clusters of kind share one Docker network, where an alias that two containers have, eg
    // the "kind-registry" of kind's own local registry setup, is answered with both, so that the cluster may reach the other registry
    private final String registryAlias = "registry-" + clusterName;
    private final Path kubeconfig;
    private GenericContainer<?> registry;
    private int hostPort;

    public KindHelper() throws IOException {
        directory = Files.createTempDirectory("afon-kind-");
        kubeconfig = directory.resolve("kubeconfig");
    }

    /**
     * @return the tools that are not installed
     */
    public static List<String> missingTools() {
        List<String> missing = new ArrayList<>();
        for (String tool : TOOLS) {
            try {
                Process process = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor() != 0) {
                    missing.add(tool);
                }
            } catch (IOException | InterruptedException e) {
                missing.add(tool);
            }
        }
        return missing;
    }

    /**
     * Runs a command with an environment added to this process's, without a password for cosign's keys.
     */
    public static Result run(Path workingDir, Map<String, String> env, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            builder.environment().put("COSIGN_PASSWORD", "");
            builder.environment().putAll(env);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new Result(-1, "Timed out running " + String.join(" ", command));
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            return new Result(-1, "Cannot run " + String.join(" ", command) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Interrupted running " + String.join(" ", command));
        }
    }

    /**
     * @throws IllegalStateException with the output if the command fails
     */
    public static String runOrFail(Path workingDir, Map<String, String> env, String... command) {
        Result result = run(workingDir, env, List.of(command));
        if (!result.ok()) {
            throw new IllegalStateException(String.join(" ", command) + " failed with " + result.exitCode() + ": " + result.output());
        }
        return result.output();
    }

    /**
     * Generates a cosign key pair, {@code cosign.key} without a password and {@code cosign.pub}, in a new directory.
     *
     * @return the directory
     */
    public Path generateKeyPair(String name) throws IOException {
        Path dir = Files.createDirectories(directory.resolve(name));
        runOrFail(dir, Map.of(), "cosign", "generate-key-pair");
        return dir;
    }

    /**
     * Creates the cluster and starts the registry, which the cluster reaches.
     */
    public void start() {
        logger.info("Creating the kind cluster {}", clusterName);
        runOrFail(null, Map.of(), "kind", "create", "cluster", "--name", clusterName, "--kubeconfig", kubeconfig.toString(),
                  "--wait", "180s");
        // A port chosen up front, not by Docker: on Docker Desktop, the daemon, which pushes to localhost, does not reach a port that Docker
        // allocated dynamically, as the one it reports is that of the host, where the daemon, in a VM, has another.
        hostPort = freePort();
        registry = new GenericContainer<>(REGISTRY_IMAGE)
            .withExposedPorts(REGISTRY_PORT)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(REGISTRY_PORT))))
            .waitingFor(Wait.forHttp("/v2/").forStatusCode(200));
        registry.start();
    }

    /**
     * Joins the registry to the network of the cluster, under the alias the cluster reaches it by.  Call it once everything has been pushed
     * to the registry through its published port: on Docker Desktop that port stops answering once the container is on kind's network,
     * which is IPv6-enabled.
     */
    public void connectRegistry() {
        runOrFail(null, Map.of(), "docker", "network", "connect", "--alias", registryAlias, KIND_NETWORK, registry.getContainerId());
    }

    /**
     * Installs Kyverno from the chart the stacks vendor.  Its admission controller is allowed insecure registries, as the registry is
     * plain HTTP, and only it is run, as the other controllers have no part in admission.
     */
    public void installKyverno() throws IOException {
        Path chart = directory.resolve(KYVERNO_CHART);
        try (InputStream in = KindHelper.class.getResourceAsStream("/charts/" + KYVERNO_CHART)) {
            if (in == null) {
                throw new IllegalStateException("No vendored chart " + KYVERNO_CHART + " on the classpath");
            }
            Files.copy(in, chart, StandardCopyOption.REPLACE_EXISTING);
        }
        runOrFail(null, kubeEnv(), "helm", "upgrade", "--install", "kyverno", chart.toString(), "--namespace", "kyverno",
                  "--create-namespace", "--set", "features.registryClient.allowInsecure=true",
                  "--set", "backgroundController.enabled=false", "--set", "cleanupController.enabled=false",
                  "--set", "reportsController.enabled=false", "--wait", "--timeout", "10m");
    }

    /**
     * @return the address of the registry for the host, eg {@code localhost:32768}
     */
    public String hostRegistry() {
        return "localhost:" + hostPort;
    }

    /**
     * @return the address of the registry on the network of the cluster, once it is connected to it
     */
    public String registryIp() {
        return containerIp(registry.getContainerId());
    }

    /**
     * @return the address of the API server of the cluster, ie of its control plane node
     */
    public String apiServerIp() {
        return containerIp(clusterName + "-control-plane");
    }

    private static String containerIp(String container) {
        return runOrFail(null, Map.of(), "docker", "inspect", "--format",
                         "{{(index .NetworkSettings.Networks \"" + KIND_NETWORK + "\").IPAddress}}", container);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the address of the registry for the cluster
     */
    public String clusterRegistry() {
        return registryAlias + ":" + REGISTRY_PORT;
    }

    public Result kubectl(String... args) {
        List<String> command = new ArrayList<>(List.of("kubectl"));
        command.addAll(List.of(args));
        return run(null, kubeEnv(), command);
    }

    /**
     * Applies a manifest given as JSON.
     */
    public void apply(String json) throws IOException {
        Path file = Files.createTempFile(directory, "manifest-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        runOrFail(null, kubeEnv(), "kubectl", "apply", "-f", file.toString());
    }

    /**
     * Has the cluster admit the manifest in a server-side dry run, which runs the admission webhooks and creates nothing.
     */
    public Result admit(String json) throws IOException {
        Path file = Files.createTempFile(directory, "admit-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return kubectl("apply", "--dry-run=server", "-f", file.toString());
    }

    private Map<String, String> kubeEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("KUBECONFIG", kubeconfig.toString());
        return env;
    }

    @Override
    public void close() {
        if (registry != null) {
            registry.stop();
        }
        Result deleted = run(null, Map.of(), List.of("kind", "delete", "cluster", "--name", clusterName, "--kubeconfig", kubeconfig.toString()));
        if (!deleted.ok()) {
            logger.warn("Cannot delete the kind cluster {}: {}", clusterName, deleted.output());
        }
    }
}
