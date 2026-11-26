package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.Credentials;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import health.emrys.afon.ops.kube.ExecResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.isProjectStack;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.sparkConnectToken;
import static java.lang.String.format;

/**
 * Runs a notebook in a user's own Jupyter pod, as the user would: with the pod's Python, its Spark Connect client, its network
 * policy and its certificate of Trino, rather than from this machine, as {@code smoke-notebook} does.
 *
 * <p>The pod is the configured user's ({@code USER_ID}), which JupyterHub is asked to spawn, as the network test does.  A pod
 * spawned so has no {@code OIDC_TOKEN}, which the hub only injects at a user's sign-in, so the user's token is got from Keycloak
 * here and given to the notebook as {@code OIDC_TOKEN}, with the name of the cloud's catalog as {@code CATALOG}.  The token,
 * the catalog and the notebook are sent to the pod on the standard input
 * of an exec through the Kubernetes API, with {@link KubeClient}, so the token is in no command line, and the notebook is run in
 * a temporary directory of the pod, removed afterwards, with {@code jupyter nbconvert --execute}.
 *
 * <p>Every cell is run, even after one fails, and the test fails if any cell raised an error.  The executed notebook, with its
 * outputs, is written to {@code <java.io.tmpdir>/smoke/notebooks/<notebook file name>}.
 *
 * <p>Skipped, and passes, for a project stack's TRE, whose Polaris and Spark Connect admit only the members' tokens, which the
 * configured user is not, as for {@code spark-polaris-smoke}.
 *
 * <p>usage: {@code jupyter-pod-notebook-smoke [<notebook>]}, the notebook defaulting to {@value #DEFAULT_NOTEBOOK}, relative to
 * the root of the source tree.
 */
public class JupyterPodNotebookSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(JupyterPodNotebookSmokeMain.class);

    static final String DEFAULT_NOTEBOOK = "demo/notebooks/AwsSmokeTest.ipynb";

    /**
     * The container of a user's pod that runs the notebook server, as KubeSpawner names it.
     */
    static final String NOTEBOOK_CONTAINER = "notebook";

    /**
     * How long a cell may run, and the notebook in all.
     */
    static final int      CELL_TIMEOUT_SECONDS = 600;
    static final Duration NOTEBOOK_TIMEOUT     = Duration.ofMinutes(20);

    /**
     * Reads the token, then the catalog, then the size of the notebook, then the notebook, from its standard input, runs the notebook
     * with the token as OIDC_TOKEN and the catalog as CATALOG, and writes the executed notebook to its standard output.  nbconvert's own logging goes to the standard error.
     * The notebook is read by its size, with head, rather than up to the end of the input, so that nothing hangs on an end that the
     * exec does not pass on.
     */
    static final String SCRIPT = String.join(
        "\n",
        "set -eu",
        "dir=$(mktemp -d)",
        "trap 'rm -rf \"$dir\"' EXIT",
        "IFS= read -r OIDC_TOKEN",
        "IFS= read -r CATALOG",
        "IFS= read -r size",
        "export OIDC_TOKEN CATALOG",
        "head -c \"$size\" > \"$dir/notebook.ipynb\"",
        "cd \"$dir\"",
        "jupyter nbconvert --to notebook --execute --allow-errors --ExecutePreprocessor.timeout=" + CELL_TIMEOUT_SECONDS
        + " --output executed.ipynb notebook.ipynb 1>&2",
        "cat executed.ipynb");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AfonPropertyProvider properties;
    private final AfonCatalog afonCatalog;

    JupyterPodNotebookSmokeMain(AfonPropertyProvider properties, AfonCloud cloud) {
        this.properties = properties;
        afonCatalog     = AfonCatalog.catalogFor(cloud, new AfonCatalogs(properties));
    }

    /**
     * @return what the script reads from its standard input: the token, the catalog, the size of the notebook, and the notebook
     */
    static byte[] input(String token, String catalog, byte[] notebook) throws IOException {
        if (token == null || token.isBlank() || token.contains("\n")) {
            throw new IllegalArgumentException("No usable OIDC token for the notebook");
        }
        if (catalog == null || catalog.isBlank() || catalog.contains("\n")) {
            throw new IllegalArgumentException("No usable catalog for the notebook: " + catalog);
        }
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write((token + "\n" + catalog + "\n" + notebook.length + "\n").getBytes(StandardCharsets.UTF_8));
        input.write(notebook);
        return input.toByteArray();
    }

    /**
     * @return the errors that the cells of the executed notebook raised, as "cell <n>: <name>: <value>", with any credentials
     * redacted; none if every cell ran
     */
    static List<String> parse(String executedNotebook) throws IOException {
        List<String> errors = new ArrayList<>();
        JsonNode     cells  = JSON.readTree(executedNotebook).path("cells");
        for (int i = 0; i < cells.size(); i++) {
            for (JsonNode output : cells.get(i).path("outputs")) {
                if ("error".equals(output.path("output_type").asText())) {
                    errors.add(Credentials.redact(format("cell %d: %s: %s", i, output.path("ename").asText(), output.path("evalue").asText())));
                }
            }
        }
        return errors;
    }

    /**
     * @return the executed notebook
     */
    static String runInPod(KubeClient client, String pod, Path notebook, String token, String catalog) throws Exception {
        logger.info("Running {} in pod {}, with CATALOG={}", notebook, pod, catalog);
        ExecResult result = client.exec(
            KUBERNETES_NAMESPACE, pod, NOTEBOOK_CONTAINER, NOTEBOOK_TIMEOUT,
            input(token, catalog, Files.readAllBytes(notebook)), "sh", "-c", SCRIPT);
        if (!result.isSuccess()) {
            throw new IllegalStateException(format(
                "Could not run %s in pod %s (exit %d): %s", notebook, pod, result.exitCode(),
                Credentials.redact(result.stderr())));
        }
        return result.stdout();
    }

    /**
     * @return whether the notebook ran in the user's pod with no error in any cell
     */
    List<String> run(Path notebook) throws Exception {
        if (isProjectStack(properties)) {
            throw new RuntimeException("Not run for a project's TRE, whose Polaris admits only the tokens of its members, which the configured user is not");
        }
        if (!Files.isRegularFile(notebook)) {
            throw new IllegalArgumentException("No notebook " + notebook.toAbsolutePath());
        }
        String pod;
        String executedNotebook;
        try (KubeClient client = new KubeClient(properties)) {
            pod              = new JupyterHubSpawner(client).spawnWithJupyterToken(properties);
            executedNotebook = runInPod(client, pod, notebook, sparkConnectToken(), afonCatalog.name());
        }
        Path saved = Path.of(System.getProperty("java.io.tmpdir"), "smoke", "notebooks", notebook.getFileName().toString());
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, executedNotebook);
        List<String> errors = parse(executedNotebook);
        if (errors.isEmpty()) {
            logger.info("{} ran in pod {} with no error; the executed notebook is {}", notebook.getFileName(), pod, saved);
        } else {
            logger.error(
                "{} of the cells of {} failed in pod {}, see the executed notebook {}:\n{}", errors.size(), notebook.getFileName(), pod,
                saved, String.join("\n", errors));
        }
        return errors;
    }

    public static void main(String[] args) {
        Path         notebook = Path.of(args.length > 0 ? args[0] : DEFAULT_NOTEBOOK);
        List<String> errors   = new ArrayList<>();
        try {
            SmokeConfig smokeConfig = new SmokeConfig();
            AfonCloud   cloud       = smokeConfig.cloud();
            JupyterPodNotebookSmokeMain app = new JupyterPodNotebookSmokeMain(
                smokeConfig.getProperties(),
                cloud
            );
            errors.addAll(app.run(notebook));
        } catch (Exception e) {
            String msg = format("Could not run %s in a Jupyter pod", notebook);
            logger.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("\n", errors));
        }
    }
}
