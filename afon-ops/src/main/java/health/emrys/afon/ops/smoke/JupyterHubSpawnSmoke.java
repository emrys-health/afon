package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.smoke.tasks.CliCommand;
import health.emrys.afon.ops.smoke.tasks.DeployCommand;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
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

import static health.emrys.afon.cdk.JupyterHubConfig.HUB_LABELS;
import static health.emrys.afon.cdk.JupyterHubConfig.HUB_PORT;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.config.AfonProperty.USER_ID;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

/**
 * Asks JupyterHub to spawn a user's single-user notebook pod via the hub's REST API, reached through a
 * port-forward to the hub pod, then waits until the server is ready and its pod exists. Usage:
 * <pre>
 *   JupyterHubSpawnSmoke [-n namespace] [-t timeoutSeconds] &lt;username&gt;
 * </pre>
 * Eg
 * <pre>
 *   JUPYTERHUB_API_TOKEN=... JupyterHubSpawnSmoke someone@example.com
 * </pre>
 * The username is the hub's name for the user, ie their email (see {@code username_claim} in JupyterHubConfig).
 * The token in {@code JUPYTERHUB_API_TOKEN} needs the {@code admin:servers} scope (and {@code admin:users} if the
 * user has not been created in the hub yet), eg one registered as a service in {@code c.JupyterHub.services}.
 */
public class JupyterHubSpawnSmoke {

    private static final Logger logger = LoggerFactory.getLogger(JupyterHubSpawnSmoke.class);

    private static final String USAGE = "Usage: JupyterHubSpawnSmoke [-n namespace] [-t timeoutSeconds] <username>";

    /**
     * KubeSpawner annotates each pod it spawns with the (unescaped) hub username.
     */
    private static final String USERNAME_ANNOTATION = "hub.jupyter.org/username";

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final KubeClient   client;
    private final String       namespace;
    private final String       apiToken;
    private final HttpClient   http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public JupyterHubSpawnSmoke(KubeClient client, String namespace, String apiToken) {
        this.client    = client;
        this.namespace = namespace;
        this.apiToken  = apiToken;
    }

    /**
     * @return the name of the user's notebook pod once the hub reports their server as ready.
     */
    public String spawn(String username, Duration timeout) throws Exception {
        String hubPod = findHubPod();
        try (PortForwardSmoke forward = new PortForwardSmoke(client, namespace, hubPod, 0, HUB_PORT)) {
            String userUrl = "http://localhost:" + forward.localPort() + "/hub/api/users/"
                + URLEncoder.encode(username, StandardCharsets.UTF_8);

            if (send("GET", userUrl).statusCode() == 404) {
                logger.info("User {} not known to the hub; creating", username);
                expect(send("POST", userUrl), 201);
            }

            String               server  = userUrl + "/server";
            HttpResponse<String> started = send("POST", server);
            switch (started.statusCode()) {
                case 201 -> logger.info("Server for {} started", username);
                case 202 -> logger.info("Server for {} is spawning", username);
                case 400 -> logger.info("Server for {} not started: {}", username, started.body());
                default -> expect(started, 201, 202);
            }

            awaitReady(userUrl, username, timeout);
        }
        String pod = findUserPod(username)
            .orElseThrow(() -> new IllegalStateException("Hub reports server for " + username
                                                             + " as ready but no pod is annotated " + USERNAME_ANNOTATION + "=" + username));
        logger.info("User {} has notebook pod {}/{}", username, namespace, pod);
        return pod;
    }

    private void awaitReady(String userUrl, String username, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            HttpResponse<String> response = send("GET", userUrl);
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

    private HttpResponse<String> send(String method, String url) throws IOException, InterruptedException {
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

    public static void main(String[] args) throws Exception {
        FilePropertyProvider properties = new FilePropertyProvider();


        try (KubeClient client = new KubeClient(properties)) {
            String email = "will@emrys.health";
            CliCommand command = new DeployCommand(client,
                                                   "hub-deployment",
                                                   "hub",
                                                   "jupyterhub token --config /etc/jupyterhub/jupyterhub_config.py " + email);
            List<ExecResult> results = command.run();
            assert results.size() == 1;
            ExecResult result = results.get(0);
            if (result.isSuccess()) {
                String token = result.stdout().strip();
                logger.info("JupyterHub Token: {}", token);
                String pod = new JupyterHubSpawnSmoke(client, "default", token).spawn(email, Duration.ofMinutes(5));
                System.out.println(pod);
            } else {
                String msg = "Could not get token. Error: " + result.stderr();
                logger.error(msg);
                throw new Exception(msg);
            }
        }
    }

}
