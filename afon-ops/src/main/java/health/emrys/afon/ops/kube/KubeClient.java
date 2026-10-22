package health.emrys.afon.ops.kube;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.Exec;
import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static java.lang.String.format;

public class KubeClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(KubeClient.class);
    public static final Duration TIMEOUT = Duration.ofMinutes(5);
    private final String               clusterName;
    private final AfonPropertyProvider properties;
    private final ApiClient            apiClient;
    private       ApiClient            execApiClient;

    public KubeClient(AfonPropertyProvider properties) {
        this.properties  = properties;
        this.clusterName = properties.getValue(CLUSTER_NAME);
        this.apiClient   = buildClient(properties);
        Configuration.setDefaultApiClient(KubeClient.this.apiClient);
    }

    protected ApiClient buildClient(AfonPropertyProvider properties) {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        try {
            FileReader reader = new FileReader(kubeConfigPath);
            return ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build();
        } catch (IOException e) {
            logger.error("Failed to build client from kube config {}", kubeConfigPath, e);
            throw new RuntimeException("Failed to build client from kube config " + kubeConfigPath, e);
        }
    }

    public String getClusterName() {
        return clusterName;
    }

    /**
     * Checks that the current kubectl context is the cluster of the cloud, ie one of the kind of that cloud's, and named after
     * {@code CLUSTER_NAME} as its stacks name theirs, <stack>-<CLUSTER_NAME>, as acting on another cluster is not what was asked: the
     * context is not always what it is thought to be, eg after switching env.
     *
     * @throws IllegalStateException if it is not
     */
    public void requireContextOf(AfonCloud cloud) {
        String context = getContext();
        boolean eks = context.startsWith("arn:aws:eks:");
        boolean gke = context.startsWith("gke_");
        switch (cloud) {
            case AWS -> {
                if (!eks) {
                    throw wrongContext(context, "is not an EKS cluster", "the AWS one, eg with 'afon-cli.sh aws-update-kube-config tre'");
                }
            }
            case GCP -> {
                if (!gke) {
                    throw wrongContext(context, "is not a GKE cluster", "the GCP one, eg with 'afon-cli.sh gcp-update-kube-config tre'");
                }
            }
            case AZURE -> {
                if (eks || gke) {
                    throw wrongContext(context, "is not an AKS cluster", "the Azure one, eg with 'afon-cli.sh azure-update-kube-config tre'");
                }
            }
        }
        // the cluster is named last: arn:aws:eks:<region>:<account>:cluster/<name>, gke_<project>_<region>_<name>, else the context
        String cluster = context.substring(Math.max(context.lastIndexOf('/'), context.lastIndexOf('_')) + 1);
        if (clusterName != null && !clusterName.isBlank() && !cluster.equals(clusterName) && !cluster.endsWith("-" + clusterName)) {
            throw wrongContext(context, "is not a cluster of this env, ie named " + clusterName + " or <stack>-" + clusterName,
                               "the cluster of the stack, as CLUSTER_NAME says");
        }
    }

    private static IllegalStateException wrongContext(String context, String problem, String pointAt) {
        return new IllegalStateException(format("The kubectl context '%s' %s: point kubectl at %s", context, problem, pointAt));
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    /**
     * @return the name of the current context of the kube config, ie that of the cluster that kubectl, and this client, act on
     */
    public String getContext() {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        try (FileReader reader = new FileReader(kubeConfigPath)) {
            return KubeConfig.loadKubeConfig(reader).getCurrentContext();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the kube config " + kubeConfigPath, e);
        }
    }

    public List<V1Service> listServices() throws ApiException {
        return new CoreV1Api().listNamespacedService("default").execute().getItems();
    }

    public List<V1Pod> listPodsBeginningWith(String prefix) throws ApiException {
        return listPods()
            .stream()
            .filter(x -> nameOfPod(x).startsWith(prefix)
        ).toList();
    }

    public static @Nullable String nameOfPod(V1Pod x) {
        return x.getMetadata().getName();
    }

    /**
     * @return the name of a running pod of the default namespace with the label, eg {@code app=polaris}
     */
    public Optional<String> runningPodWithLabel(String labelSelector) throws ApiException {
        return new CoreV1Api().listNamespacedPod("default").labelSelector(labelSelector).execute().getItems().stream()
                              .filter(pod -> pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()))
                              .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                              .map(KubeClient::nameOfPod)
                              .findFirst();
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
            int exitCode = process.exitValue();
            if (exitCode == -1) {
                // The client discards the status channel's message unless it carries an exit code, so a
                // command that never started (eg executable not found) leaves stdout and stderr empty.
                logger.warn("Command {} in {}/{} did not run (eg executable not found). Each argument must be a"
                        + " separate element of the command array", Arrays.toString(command), namespace, pod);
            }
            return new ExecResult(exitCode, stdout.join(), stderr.join(), String.join(" ", command));
        } finally {
            process.destroy();
        }
    }

    public ExecResult exec(String pod, String... command)
            throws ApiException, IOException, InterruptedException, TimeoutException {
        return exec("default", pod, null, TIMEOUT, command);
    }

    /**
     * The equivalent of {@code kubectl exec -n <namespace> deploy/<deployment> -- <command...>}: runs the command
     * in one of the deployment's running pods.
     */
    public ExecResult execInDeployment(String namespace, String deployment, String container,
                                       String... command)
            throws ApiException, IOException, InterruptedException, TimeoutException {
        return exec(namespace, podForDeployment(namespace, deployment), container, TIMEOUT, command);
    }

    /**
     * Resolves a deployment to one of its running pods, as {@code kubectl} does for {@code deploy/<name>}.
     */
    public String podForDeployment(String namespace, String deployment) throws ApiException {
        V1Deployment d = new AppsV1Api().readNamespacedDeployment(deployment, namespace).execute();
        Map<String, String> matchLabels = d.getSpec().getSelector().getMatchLabels();
        if (matchLabels == null || matchLabels.isEmpty()) {
            throw new IllegalStateException("Deployment " + namespace + "/" + deployment + " has no matchLabels selector");
        }
        String selector = matchLabels.entrySet().stream()
                                     .map(e -> e.getKey() + "=" + e.getValue())
                                     .collect(Collectors.joining(","));
        return new CoreV1Api().listNamespacedPod(namespace).labelSelector(selector).execute().getItems().stream()
                              .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                              .filter(pod -> pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()))
                              .map(pod -> nameOfPod(pod))
                              .findFirst()
                              .orElseThrow(() -> new IllegalStateException(
                                      "No running pod for deployment " + namespace + "/" + deployment + " (" + selector + ")"));
    }

    /**
     * Opens a single port-forward stream to {@code port} on the pod, like one connection through
     * {@code kubectl port-forward}. Each stream carries one TCP connection, so open one per local connection.
     * The caller must close the result.
     */
    public PortForward.PortForwardResult portForward(String namespace, String pod, int port)
    throws ApiException, IOException {
        return new PortForward(execApiClient()).forward(namespace, pod, List.of(port));
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                String read = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                logger.info("Read: {}", read);
                return read;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Exec and port-forward stream over a websocket, so it needs a client without the default read timeout, otherwise
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
