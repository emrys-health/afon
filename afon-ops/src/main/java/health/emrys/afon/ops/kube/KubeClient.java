package health.emrys.afon.ops.kube;

import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;

public class KubeClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(KubeClient.class);
    private final String clusterName;
    private final AfonPropertyProvider properties;
    private final ApiClient apiClient;
    private ApiClient execApiClient;

    public KubeClient(AfonPropertyProvider properties) {
        this.properties = properties;
        this.clusterName = properties.getValue(CLUSTER_NAME);
        this.apiClient = buildClient(properties);
        Configuration.setDefaultApiClient(KubeClient.this.apiClient);
    }

    protected ApiClient buildClient(AfonPropertyProvider properties) {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        try {
            FileReader reader = new FileReader(kubeConfigPath);
            return ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build();
        } catch (IOException e) {
            logger.error("Failed to build client from kube config {}",kubeConfigPath, e);
            throw new RuntimeException("Failed to build client from kube config "+kubeConfigPath, e);
        }
    }

    public String getClusterName() {
        return clusterName;
    }

    public List<V1Service> listServices() throws ApiException {
        return new CoreV1Api().listNamespacedService("default").execute().getItems();
    }

    public List<V1Pod> listPods() throws ApiException {
        return new CoreV1Api().listNamespacedPod("default").execute().getItems();
    }

    /**
     * The equivalent of {@code kubectl exec -n <namespace> <pod> [-c <container>] -- <command...>}.
     * Blocks until the command completes or the timeout elapses.
     *
     * @param container may be null, in which case the pod must have exactly one container
     */
    public ExecResult exec(String namespace, String pod, String container, Duration timeout, String... command)
            throws ApiException, IOException, InterruptedException, TimeoutException {
        logger.info("Executing\n{}\nin {}/{}{}", String.join(" ", command), namespace, pod,
                container == null ? "" : " (container " + container + ")");
        Process process = new Exec(execApiClient())
                .newExecutionBuilder(namespace, pod, command)
                .setContainer(container)
                .setStdin(false)
                .setTty(false)
                .execute();
        try {
            process.getOutputStream().close();
            // Drain both streams concurrently so neither blocks the remote process by filling up.
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Command '" + String.join(" ", command) + "' in " + namespace + "/" + pod
                        + " did not complete within " + timeout);
            }
            return new ExecResult(process.exitValue(), stdout.join(), stderr.join(), String.join(" ", command));
        } finally {
            process.destroy();
        }
    }

    public ExecResult exec(String pod, String... command)
            throws ApiException, IOException, InterruptedException, TimeoutException {
        return exec("default", pod, null, Duration.ofMinutes(5), command);
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Exec streams over a websocket, so it needs a client without the default read timeout, otherwise
     * any command that is quiet for longer than that is cut off. Kept separate so the other API calls
     * still time out.
     */
    private synchronized ApiClient execApiClient() {
        if (execApiClient == null) {
            execApiClient = buildClient(properties);
            execApiClient.setReadTimeout(0);
        }
        return execApiClient;
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing Kubernetes client");
        closeClient(apiClient);
        closeClient(execApiClient);
        Configuration.setDefaultApiClient(null);
    }

    private static void closeClient(ApiClient apiClient) throws IOException {
        if (apiClient != null && apiClient.getHttpClient() != null) {
            OkHttpClient httpClient = apiClient.getHttpClient();
            if (httpClient.cache() != null) {
                logger.info("Closing cache");
                httpClient.cache().close();
            }
            if (httpClient.connectionPool() != null) {
                logger.info("Closing connection pool");
                httpClient.connectionPool().evictAll();
            }
            Dispatcher dispatcher = httpClient.dispatcher();
            if (dispatcher != null && dispatcher.executorService() != null) {
                logger.info("Closing executor service");
                dispatcher.executorService().shutdown();
            }
        }
    }
}
