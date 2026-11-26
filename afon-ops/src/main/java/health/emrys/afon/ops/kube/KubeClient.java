package health.emrys.afon.ops.kube;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import io.kubernetes.client.Copy;
import io.kubernetes.client.Exec;
import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
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

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(KubeClient.class);
    public static final Duration TIMEOUT = Duration.ofMinutes(5);
    private final String               clusterName;
    private final AfonPropertyProvider properties;
    private final ApiClient            apiClient;
    private       ApiClient            execApiClient;
    private final String               kubeConfigPath;

    public KubeClient(AfonPropertyProvider properties) {
        this(properties, ConfigUtils.opsKubeConfigPath());
    }

    /**
     * Acts on the cluster of the kube config, whatever {@code KUBECONFIG}, and the stack that the ops commands act on, say, eg that
     * of another project's stack.
     */
    public KubeClient(AfonPropertyProvider properties, String kubeConfigPath) {
        this.kubeConfigPath = kubeConfigPath;
        this.properties  = properties;
        this.clusterName = properties.getValue(CLUSTER_NAME);
        this.apiClient   = buildClient(properties);
        Configuration.setDefaultApiClient(KubeClient.this.apiClient);
    }

    protected ApiClient buildClient(AfonPropertyProvider properties) {
        try {
            FileReader reader     = new FileReader(kubeConfigPath);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            // which cluster is acted on, as it may not be the one thought, eg KUBECONFIG of an IDE's run configuration
            logger.info("Using kube config {}: context '{}', server {}", kubeConfigPath, kubeConfig.getCurrentContext(),
                        kubeConfig.getServer());
            return ClientBuilder.kubeconfig(kubeConfig).build();
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
        requireContextKindOf(cloud);
        // the cluster is named last: arn:aws:eks:<region>:<account>:cluster/<name>, gke_<project>_<region>_<name>, else the context
        String context = getContext();
        String cluster = context.substring(Math.max(context.lastIndexOf('/'), context.lastIndexOf('_')) + 1);
        if (clusterName != null && !clusterName.isBlank() && !cluster.equals(clusterName) && !cluster.endsWith("-" + clusterName)) {
            throw wrongContext(context, "is not a cluster of this env, ie named " + clusterName + " or <stack>-" + clusterName,
                               "the cluster of the stack, as CLUSTER_NAME says");
        }
    }

    /**
     * Checks only that the current kubectl context is a cluster of the kind of the cloud's, not whose it is: for the kube config of a cluster that is not
     * this env's, eg that of the identity project, which a project reaches Keycloak in, and which is named after that project, not after {@code CLUSTER_NAME}.
     *
     * @throws IllegalStateException if it is not
     */
    public void requireContextKindOf(AfonCloud cloud) {
        String context = getContext();
        boolean eks = context.startsWith("arn:aws:eks:");
        boolean gke = context.startsWith("gke_");
        switch (cloud) {
            case AWS -> {
                if (!eks) {
                    throw wrongContext(context, "is not an EKS cluster", "the AWS one, eg 'afon-cli.sh aws-update-kube-config tre' prints the KUBECONFIG to export");
                }
            }
            case GCP -> {
                if (!gke) {
                    throw wrongContext(context, "is not a GKE cluster", "the GCP one, eg 'afon-cli.sh gcp-update-kube-config tre' prints the KUBECONFIG to export");
                }
            }
            case AZURE -> {
                if (eks || gke) {
                    throw wrongContext(context, "is not an AKS cluster", "the Azure one, eg 'afon-cli.sh azure-update-kube-config tre' prints the KUBECONFIG to export");
                }
            }
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
        try (FileReader reader = new FileReader(kubeConfigPath)) {
            return KubeConfig.loadKubeConfig(reader).getCurrentContext();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the kube config " + kubeConfigPath, e);
        }
    }

    /**
     * @return the value of the key of the secret, decoded
     * @throws IllegalStateException if the secret has no such key
     */
    public byte[] secretValue(String namespace, String name, String key) throws ApiException {
        Map<String, byte[]> data = new CoreV1Api().readNamespacedSecret(name, namespace).execute().getData();
        if (data == null || !data.containsKey(key)) {
            throw new IllegalStateException(String.format("Secret %s/%s has no key %s", namespace, name, key));
        }
        return data.get(key);
    }

    public List<V1Service> listServices() throws ApiException {
        return new CoreV1Api().listNamespacedService("default").execute().getItems();
    }

    /**
     * @return the ingresses of every namespace, as an ingress of any of them exposes the cluster
     */
    public List<V1Ingress> listIngresses() throws ApiException {
        return new NetworkingV1Api().listIngressForAllNamespaces().execute().getItems();
    }

    public List<V1Pod> listPodsBeginningWith(String prefix) throws ApiException {
        return listPods()
            .stream()
            .filter(x -> nameOfPod(x).startsWith(prefix)
        ).toList();
    }

    public V1Pod getOnlyPodBegginingWith(String prefix) throws ApiException {
        List<V1Pod> pods = listPodsBeginningWith(prefix);
        if (pods.size() != 1) {
            throw new RuntimeException(format("No pod starting '%s'", prefix));
        }
        V1Pod pod = pods.get(0);
        return pod;
    }

    /**
     * Waits for the only pod whose name begins with the prefix to be ready, eg one that Helm installs without waiting for it.
     *
     * @return the ready pod
     * @throws TimeoutException if it is not ready within the timeout
     */
    public V1Pod waitForOnlyPodBeginningWithToBeReady(String prefix, Duration timeout)
        throws ApiException, TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<V1Pod> pods = listPodsBeginningWith(prefix);
            if (pods.size() == 1 && isReady(pods.get(0))) {
                return pods.get(0);
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException(format("No single ready pod starting '%s' after %s (%d such pods)",
                                                  prefix, timeout, pods.size()));
            }
            logger.info("Waiting for the pod starting '{}' to be ready ({} such pods)", prefix, pods.size());
            TimeUnit.SECONDS.sleep(10);
        }
    }

    static boolean isReady(V1Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getConditions() != null
               && pod.getStatus().getConditions().stream()
                     .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
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
        return exec(namespace, pod, container, timeout, null, command);
    }

    /**
     * The equivalent of {@code kubectl exec -i -n <namespace> <pod> [-c <container>] -- <command...> < stdin}: the command reads the
     * given bytes from its standard input, which is then closed.  The input is never logged, so it may hold a credential, which is
     * then in no command line.  A command should not wait for the end of its input, which not every server passes on, but read as
     * many bytes as it is told to.  Blocks until the command completes or the timeout elapses.
     *
     * @param container may be null, in which case the pod must have exactly one container
     * @param stdin     the standard input of the command, or null for none
     */
    public ExecResult exec(String namespace, String pod, String container, Duration timeout, byte @Nullable [] stdin, String... command)
    throws ApiException, IOException, InterruptedException, TimeoutException {
        logger.info("Executing\n{}\nin {}/{}{}{}", Credentials.redact(String.join(" ", command)), namespace, pod,
                    container == null ? "" : " (container " + container + ")",
                    stdin == null ? "" : ", with " + stdin.length + " bytes of input");
        Process process = new Exec(execApiClient())
            .newExecutionBuilder(namespace, pod, command)
            .setContainer(container)
            .setStdin(stdin != null)
            .setTty(false)
            .execute();
        try {
            // Drain both streams concurrently so neither blocks the remote process by filling up, before writing its input, which the
            // process may answer before it has read all of it.
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            try (var input = process.getOutputStream()) {
                if (stdin != null) {
                    input.write(stdin);
                    input.flush();
                }
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Command '" + Credentials.redact(String.join(" ", command)) + "' in " + namespace + "/" + pod
                                               + " did not complete within " + timeout);
            }
            int exitCode = process.exitValue();
            if (exitCode == -1) {
                // The client discards the status channel's message unless it carries an exit code, so a
                // command that never started (eg executable not found) leaves stdout and stderr empty.
                logger.warn("Command {} in {}/{} did not run (eg executable not found). Each argument must be a"
                        + " separate element of the command array", Credentials.redact(Arrays.toString(command)), namespace, pod);
            }
            // the command is kept for reporting only, so without its credentials
            return new ExecResult(exitCode, stdout.join(), stderr.join(), Credentials.redact(String.join(" ", command)), pod);
        } finally {
            process.destroy();
        }
    }

    /**
     * Copies a local file into the pod, as {@code kubectl cp <local> <pod>:<remote>} does, so that the pod needs {@code tar}.
     *
     * @param remote the path of the file in the pod, whose directory exists
     */
    public void copyToPod(String pod, java.nio.file.Path local, String remote) throws ApiException, IOException {
        new Copy(execApiClient()).copyFileToPod("default", pod, null, local, java.nio.file.Path.of(remote));
    }

    /**
     * Copies a file of the pod to a local file, as {@code kubectl cp <pod>:<remote> <local>} does.
     */
    public void copyFromPod(String pod, String remote, java.nio.file.Path local) throws ApiException, IOException {
        new Copy(execApiClient()).copyFileFromPod("default", pod, null, remote, local);
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
