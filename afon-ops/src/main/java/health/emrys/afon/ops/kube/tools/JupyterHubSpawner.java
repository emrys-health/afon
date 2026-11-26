package health.emrys.afon.ops.kube.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.project.ProjectStackUserProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import health.emrys.afon.cdk.configs.JupyterHubConfig;

import static health.emrys.afon.cdk.configs.JupyterHubConfig.HUB_LABELS;
import static health.emrys.afon.cdk.configs.JupyterHubConfig.HUB_PORT;
import static health.emrys.afon.config.AfonProperty.USER_ID;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;
import static java.lang.String.format;

/**
 * Asks JupyterHub to spawn a user's single-user notebook pod via the hub's REST API, reached through a
 * port-forward to the hub pod, then waits until the server is ready and its pod exists.
 *
 * Unfortunately, this way of spawning the Jupyter pod does not add an OIDC_TOKEN environment variable to the env.
 */
public class JupyterHubSpawner {

    private static final Logger logger = LoggerFactory.getLogger(JupyterHubSpawner.class);

    /**
     * KubeSpawner annotates each pod it spawns with the (unescaped) hub username.
     */
    private static final String USERNAME_ANNOTATION = "hub.jupyter.org/username";

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private AfonUserProvider users;

    public static void main(String[] args) throws Exception {
        FilePropertyProvider properties = new FilePropertyProvider();
        try (KubeClient client = new KubeClient(properties)) {
            JupyterHubSpawner app = new JupyterHubSpawner(client, KUBERNETES_NAMESPACE);
            app.spawnWithJupyterToken(properties);
        }
    }

    private final KubeClient   client;
    private final String       namespace;
    private final HttpClient   http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public JupyterHubSpawner(KubeClient client) {
        this(client, KUBERNETES_NAMESPACE);
    }

    public JupyterHubSpawner(KubeClient client, String namespace) {
        this.client    = client;
        this.namespace = namespace;
        users          = ProjectStackUserProvider.forActiveConfig();
    }

    /**
     * @return the name of the user's notebook pod once the hub reports their server as ready.
     */
    public String spawnWithJupyterToken(AfonPropertyProvider properties) throws Exception {
        String email = findEmailFor(properties.getValue(USER_ID));
        String token = apiToken(email);
        if (token == null) {
            throw new IllegalStateException("Could not get a token for the hub's API: the hub has no user " + email);
        }
        logger.warn(format("Note that user with email '%s' will not have an OIDC token if they then login through Guacamole", email));
        String pod = spawn(email, Duration.ofMinutes(5), token);
        logger.info("Pod {} running", pod);
        return pod;
    }

    /**
     * Stops the user's notebook server, if the hub has one, through the hub's API, so that the hub knows it is gone and starts a new one, with the user's token as it is
     * then, at their next sign-in; and waits for its pod to be gone.
     */
    public void stopServer(String email, Duration timeout) throws Exception {
        String apiToken = apiToken(email);
        if (apiToken == null) {
            // the hub has never had the user, who has signed in to it never, so there is no server of theirs to stop
            logger.info("The hub has no user {}, so there is no server to stop", email);
            return;
        }
        try (PortForwarder forward = new PortForwarder(client, namespace, findHubPod(), 0, HUB_PORT)) {
            String server = "http://localhost:" + forward.localPort() + "/hub/api/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "/server";
            HttpResponse<String> stopped = send("DELETE", server, apiToken);
            // 204 stopped, 202 stopping, 400 not running, 404 the hub does not know the user: the user has no server to stop
            if (!List.of(202, 204, 400, 404).contains(stopped.statusCode())) {
                expect(stopped, 202, 204);
            }
        }
        Instant deadline = Instant.now().plus(timeout);
        while (findUserPod(email).isPresent()) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("The pod of " + email + " is still there after " + timeout + ", though the hub was asked to stop the server");
            }
            Thread.sleep(2_000);
        }
    }

    /**
     * @return the name of the user's notebook pod, once there is one that was made after {@code after}, which is running and ready: the pod of the sign-in that
     * was made then, which has the user's token as it was then
     */
    public String awaitPod(String email, Instant after, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            for (V1Pod pod : client.listPods()) {
                Map<String, String> annotations = pod.getMetadata() == null ? null : pod.getMetadata().getAnnotations();
                boolean theirs = annotations != null && email.equals(annotations.get(USERNAME_ANNOTATION));
                boolean newer = pod.getMetadata() != null && pod.getMetadata().getCreationTimestamp() != null
                                && pod.getMetadata().getCreationTimestamp().toInstant().isAfter(after.minusSeconds(5));
                boolean ready = pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()) && pod.getStatus().getContainerStatuses() != null
                                && !pod.getStatus().getContainerStatuses().isEmpty() && pod.getStatus().getContainerStatuses().stream().allMatch(c -> Boolean.TRUE.equals(c.getReady()));
                if (theirs && newer && ready && pod.getMetadata().getDeletionTimestamp() == null) {
                    return pod.getMetadata().getName();
                }
            }
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("No notebook pod of " + email + " that is running and was made since the sign-in, within " + timeout
                                           + ": did the hub start their server? (kubectl get pods; the hub's logs)");
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private String spawn(String email, Duration timeout, String apiToken) throws Exception {
        String hubPod = findHubPod();
        try (PortForwarder forward = new PortForwarder(client, namespace, hubPod, 0, HUB_PORT)) {
            String userUrl = "http://localhost:" + forward.localPort() + "/hub/api/users/"
                + URLEncoder.encode(email, StandardCharsets.UTF_8);

            if (send("GET", userUrl, apiToken).statusCode() == 404) {
                // the smoke service may not make users, which would give it their auth_state: a user is made by their first sign-in
                throw new IllegalStateException("The hub has no user " + email + ": they are made by their first sign-in to the hub");
            }

            String               server  = userUrl + "/server";
            HttpResponse<String> started = send("POST", server, apiToken);
            switch (started.statusCode()) {
                case 201 -> logger.info("Server for {} started", email);
                case 202 -> logger.info("Server for {} is spawning", email);
                case 400 -> logger.info("Server for {} not started: {}", email, started.body());
                default -> expect(started, 201, 202);
            }

            awaitReady(userUrl, email, timeout, apiToken);
        }
        String pod = findUserPod(email)
            .orElseThrow(() -> new IllegalStateException("Hub reports server for " + email
                                                             + " as ready but no pod is annotated " + USERNAME_ANNOTATION + "=" + email));
        logger.info("User {} has notebook pod {}/{}", email, namespace, pod);
        return pod;
    }

    public static String toPassword(AfonUser x) {
        return x.password();
    }

    public static String toEmail(AfonUser x) {
        return x.email();
    }

    private String findEmailFor(String username) throws Exception {
        return propertyOf(username, JupyterHubSpawner::toEmail);
    }

    public @NonNull String propertyOf(String username, Function<AfonUser, String> fn) {
        String email = users
            .getUsers()
            .stream()
            .filter(x -> x.username().equals(username))
            .map(fn)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No user called " + username));
        return email;
    }

    private void awaitReady(String userUrl, String username, Duration timeout, String apiToken) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            HttpResponse<String> response = send("GET", userUrl, apiToken);
            expect(response, 200);
            JsonNode server = mapper.readTree(response.body()).path("servers").path("");
            if (server.path("ready").asBoolean(false)) {
                logger.info("Server for {} is ready at {}", username, server.path("url").asText());
                return;
            }
            if (server.isMissingNode()) {
                throw new IllegalStateException("Hub has no server for " + username
                                                    + "; the spawn failed (see the hub logs). User: " + response.body());
            }
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Server for " + username + " not ready within " + timeout
                                               + ". Pending: " + server.path("pending").asText());
            }
            logger.info("Waiting for server for {} (pending: {})", username, server.path("pending").asText());
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private HttpResponse<String> send(String method, String url, String apiToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                         .timeout(Duration.ofSeconds(30))
                                         .header("Authorization", "token " + apiToken)
                                         .method(method, HttpRequest.BodyPublishers.noBody())
                                         .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("{} {} -> {} {}", method, url, response.statusCode(), response.body());
        return response;
    }

    private static void expect(HttpResponse<String> response, int... statusCodes) {
        int actual = response.statusCode();
        for (int code : statusCodes) {
            if (actual == code) {
                return;
            }
        }
        throw new IllegalStateException(response.request().method() + " " + response.uri() + " returned "
                                            + actual + ": " + response.body());
    }

    private String findHubPod() throws ApiException {
        return client.listPods().stream()
                     .filter(pod -> labelsMatch(pod, HUB_LABELS))
                     .filter(pod -> pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()))
                     .map(pod -> pod.getMetadata().getName())
                     .findFirst()
                     .orElseThrow(() -> new IllegalStateException("No running JupyterHub pod labelled " + HUB_LABELS));
    }

    private Optional<String> findUserPod(String username) throws ApiException {
        return client.listPods().stream()
                     .filter(pod -> pod.getMetadata() != null && pod.getMetadata().getAnnotations() != null)
                     .filter(pod -> username.equals(pod.getMetadata().getAnnotations().get(USERNAME_ANNOTATION)))
                     .map(pod -> pod.getMetadata().getName())
                     .findFirst();
    }

    private static boolean labelsMatch(V1Pod pod, Map<String, String> labels) {
        Map<String, String> podLabels = pod.getMetadata() == null ? null : pod.getMetadata().getLabels();
        return podLabels != null && podLabels.entrySet().containsAll(labels.entrySet());
    }

    /** What an email is made of, for the one that is put in the command that mints a token. */
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+");

    /** The tokens for the hub's API that have been got, by email: they are kept for the life of this, as getting one is a call into the hub. */
    private final Map<String, String> tokens = new java.util.HashMap<>();

    /**
     * The token for the hub's API with which to make the user, start their server and stop it: that of the hub's service for the smoke tests, which the hub's
     * secret holds, see {@link JupyterHubConfig#SMOKE_SERVICE}.  A hub that was deployed before it has none, so for such a hub the hub mints the token of the user,
     * with its own command, which it keeps for ever: it is a fall back, which a deploy of the TRE does away with.  The token is never logged.
     *
     * @return the token, or {@code null} if the hub has none of its service and no such user
     */
    private String apiToken(String email) throws Exception {
        if (tokens.containsKey(email)) {
            return tokens.get(email);
        }
        String token;
        try {
            token = new String(client.secretValue(namespace, JupyterHubConfig.SECRET_NAME, JupyterHubConfig.SMOKE_API_TOKEN_KEY), StandardCharsets.UTF_8).strip();
        } catch (IllegalStateException | ApiException e) {
            logger.warn("The hub has no service for the smoke tests ({}): it was deployed without it, so a token of {} is minted, which it keeps for ever. Deploy the TRE again to do away with that",
                        e.getMessage(), email);
            token = mintTokenFor(email);
        }
        if (token != null) {
            tokens.put(email, token);
        }
        return token;
    }

    /**
     * @return the token, or {@code null} if the hub has no such user
     */
    private String mintTokenFor(String email) throws Exception {
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Not an email to mint a hub token for: " + email);
        }
        ExecResult result = client.execInDeployment(namespace, "hub-deployment", "hub", "jupyterhub", "token", "--config", "/etc/jupyterhub/jupyterhub_config.py", email);
        if (result.isSuccess()) {
            return result.stdout().strip();
        }
        if (result.stderr().contains("No such user")) {
            return null;
        }
        throw new IllegalStateException("Could not get a hub token for " + email + ": " + result.stderr());
    }

}
