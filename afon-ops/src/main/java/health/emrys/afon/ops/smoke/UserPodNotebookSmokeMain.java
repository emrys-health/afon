package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.configs.SparkWorkloads;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.UserPod;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.kube.tools.PodNotebookRunner;
import health.emrys.afon.ops.smoke.config.SmokeConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Runs notebooks in the notebook pod of a user, as the user does: with the pod's own python, its Spark Connect client, its network policy and its certificate of Trino, see
 * {@link PodNotebookRunner}.  Run it as {@code user-pod-notebook-smoke <results-dir> <notebook.ipynb>...}, eg through {@code smoke-notebook}, which finds the notebooks.  The
 * executed notebooks are written to the results dir.  Exits non-zero if a notebook fails.  {@code AFON_NOTEBOOK_CELL_TIMEOUT} is the most that a cell may take, 300 seconds by
 * default.
 * <p>
 * Whose pod it is depends on the mode:
 * <ul>
 *   <li>In project mode, the project's smoke member's, signed in to the TRE's JupyterHub as they would be from a browser, which starts their pod with their token: the member's
 *       token, with the project and the second factor, is the pod's own.  It needs the credentials of the smoke member, from {@code manage-identity smoke-credentials}.</li>
 *   <li>In non-project mode, that of {@code USER_ID}, who is an admin: JupyterHub is asked, through its API, to start their pod, which then has no token of the
 *       sign-in, as the hub puts it in a pod only at a user's sign-in, so the token that Keycloak gives the user's client is given to the notebooks as {@code OIDC_TOKEN}.</li>
 * </ul>
 * What the notebooks are told is {@code AFON_TARGET}, {@code AFON_PROJECT}, {@code AFON_CATALOG}, {@code AFON_FORBIDDEN_CATALOG}, {@code POLARIS_DOMAIN_NAME} and
 * {@code OIDC_DOMAIN_NAME}, from this command's own environment, where {@code smoke-notebook} has put them, and, for a user that is not a member, {@code OIDC_TOKEN} and
 * {@code SPARK_REMOTE}.  It goes to the pod on the standard input of the command that runs the notebook, so no token is in a command line.
 */
public class UserPodNotebookSmokeMain {

    /** The variables of this command's environment that the notebooks are given. */
    static final List<String> PASSED_ON = List.of("AFON_TARGET", "AFON_PROJECT", "AFON_CATALOG", "AFON_FORBIDDEN_CATALOG", "POLARIS_DOMAIN_NAME", "OIDC_DOMAIN_NAME");

    /** @return the variables of the environment that the notebooks are given, those of {@link #PASSED_ON} that are set */
    static Map<String, String> forwardedEnvironment(Map<String, String> environment) {
        Map<String, String> passed = new LinkedHashMap<>();
        for (String name : PASSED_ON) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                passed.put(name, value);
            }
        }
        return passed;
    }

    /**
     * @return the address of Spark Connect in the cluster, with no token after it, as the hub puts it, with one, in the environment of the pod of a user who signed in: a notebook
     * that puts the token after it, as the user's does, takes this
     */
    static String sparkRemoteWithoutToken() {
        String url = SparkWorkloads.SPARK_CONNECT_URL;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("UserPodNotebookSmokeMain requires arguments: <results-dir> <notebook.ipynb>...");
            System.exit(2);
        }
        Path results = Path.of(args[0]);
        List<Path> notebooks = java.util.Arrays.stream(args).skip(1).map(Path::of).toList();
        AfonPropertyProvider properties = SmokeConfig.smokeTestProperties();
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        Map<String, String> environment = forwardedEnvironment(System.getenv());
        int cellTimeout = Integer.parseInt(System.getenv().getOrDefault("AFON_NOTEBOOK_CELL_TIMEOUT", "300"));
        boolean project = SmokeConfig.isProjectStack(properties);
        // the token of a user that is not a member of a project is got before the pod is started: if it cannot be, nothing is started
        String adminToken = project ? null : SmokeConfig.sparkConnectToken();

        try (KubeClient client = new KubeClient(properties)) {
            JupyterHubSpawner spawner = new JupyterHubSpawner(client);
            if (!project) {
                environment.put("OIDC_TOKEN", adminToken);
                environment.put("SPARK_REMOTE", sparkRemoteWithoutToken());
            }
            UserPod.Hub hub = UserPod.of(properties, cloud, spawner);
            PodNotebookRunner run = new PodNotebookRunner(hub, pod -> new UserPod.Pod() {
                @Override
                public ExecResult exec(String... command) throws Exception {
                    return client.exec("default", pod, null, Duration.ofMinutes(20), command);
                }

                @Override
                public ExecResult exec(byte[] stdin, String... command) throws Exception {
                    return client.exec("default", pod, null, Duration.ofMinutes(20), stdin, command);
                }

                @Override
                public void copyTo(Path local, String remote) throws Exception {
                    client.copyToPod(pod, local, remote);
                }

                @Override
                public void copyFrom(String remote, Path local) throws Exception {
                    client.copyFromPod(pod, remote, local);
                }
            }, cellTimeout, System.out);
            List<PodNotebookRunner.Result> outcomes = run.run(notebooks, results, environment);
            List<String> failed = outcomes.stream().filter(result -> !result.passed()).map(PodNotebookRunner.Result::notebook).toList();
            if (!failed.isEmpty()) {
                System.err.printf("Notebooks failed (%d of %d): %s%n", failed.size(), outcomes.size(), String.join(" ", failed));
                System.exit(1);
            }
            System.out.printf("All notebooks (%d) passed%n", outcomes.size());
        }
    }
}
