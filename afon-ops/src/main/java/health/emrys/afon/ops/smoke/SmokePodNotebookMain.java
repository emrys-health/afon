package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.kube.tools.NotebookPodRunner;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.config.AfonProperty.SMOKE_MEMBER;

/**
 * Runs notebooks in the notebook pod of a project's smoke member, as a member of the project does: the smoke member is signed in to the TRE's JupyterHub, as they would be
 * from a browser, which starts their pod with their token, and the notebooks are run in it, see {@link NotebookPodRunner}.
 * <p>
 * Run it, in the project stack's home, as {@code smoke-pod-notebook <results-dir> <notebook.ipynb>...}, eg through {@code smoke-notebook}.  It needs the credentials of the smoke member,
 * from {@code manage-identity smoke-credentials}.  {@code AFON_TARGET} and {@code AFON_PROJECT}, if set, are set for the notebooks, and {@code AFON_NOTEBOOK_CELL_TIMEOUT}
 * is the most that a cell may take, 300 seconds by default.  The executed notebooks are written to the results dir.  Exits non-zero if a notebook fails.
 */
public class SmokePodNotebookMain {

    private static final List<String> PASSED_ON = List.of("AFON_TARGET", "AFON_PROJECT");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("SmokePodNotebookMain requires arguments: <results-dir> <notebook.ipynb>...");
            System.exit(2);
        }
        Path results = Path.of(args[0]);
        List<Path> notebooks = java.util.Arrays.stream(args).skip(1).map(Path::of).toList();
        AfonPropertyProvider properties = SmokeConfig.smokeTestProperties();
        String projectStack = properties.getValue(PROJECT_ID, "");
        String member = properties.getValue(SMOKE_MEMBER, "");
        if (projectStack.isBlank() || member.isBlank()) {
            throw new IOException("There is no smoke member: PROJECT_ID and SMOKE_MEMBER are set in the config of a project stack that lists smokeMembers, once configured");
        }
        Path secrets = FileProjectStackProvider.secretsDir(ProjectStackEnv.defaultAfonHome(), projectStack);
        SmokeMemberCredentials credentials = SmokeMemberCredentials.read(secrets, member).orElseThrow(() -> new IOException(
            "No credentials for " + member + " in " + secrets.resolve(SmokeMemberCredentials.FILE) + ": give them with manage-identity smoke-credentials"));
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        String jupyterDomain = properties.getCloudValue(cloud, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "");
        if (jupyterDomain.isBlank()) {
            throw new IOException(cloud.name() + "_JUPYTER_DOMAIN_NAME is not set, so there is no JupyterHub to sign in to");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        PASSED_ON.forEach(name -> {
            if (System.getenv(name) != null && !System.getenv(name).isBlank()) {
                environment.put(name, System.getenv(name));
            }
        });
        int cellTimeout = Integer.parseInt(System.getenv().getOrDefault("AFON_NOTEBOOK_CELL_TIMEOUT", "300"));

        try (KubeClient client = new KubeClient(properties)) {
            JupyterHubSpawner spawner = new JupyterHubSpawner(client);
            URI realm = URI.create(properties.getOidcIssuerUrl(cloud));
            NotebookPodRunner.Hub hub = new NotebookPodRunner.Hub() {
                @Override
                public void stopServer() throws Exception {
                    spawner.stopServer(member, Duration.ofMinutes(3));
                }

                @Override
                public void signIn() throws Exception {
                    KeyCloakMemberLogin.HubLanding landing = new KeyCloakMemberLogin(realm).signInToHub(URI.create("https://" + jupyterDomain + "/"), member,
                                                                                                          credentials.password(), credentials.totpSecret());
                    System.out.printf("Signed %s in to https://%s/, which ended at %s (HTTP %d)%n", member, jupyterDomain, landing.at().getPath(), landing.status());
                    if (landing.status() >= 400) {
                        throw new IOException("The hub refused " + member + " (HTTP " + landing.status() + " at " + landing.at().getPath() + "): " + landing.text()
                                              + ".  The hub admits the members that were listed when the TRE was deployed, so a smoke member added since is refused until the TRE"
                                              + " is deployed again: configure, then up <project-stack> --from tre");
                    }
                }

                @Override
                public String awaitPod(Instant since) throws Exception {
                    return spawner.awaitPod(member, since, Duration.ofMinutes(6));
                }
            };
            NotebookPodRunner run = new NotebookPodRunner(hub, pod -> new NotebookPodRunner.Pod() {
                @Override
                public ExecResult exec(String... command) throws Exception {
                    return client.exec("default", pod, null, Duration.ofMinutes(20), command);
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
            List<NotebookPodRunner.Result> outcomes = run.run(notebooks, results, environment);
            List<String> failed = outcomes.stream().filter(result -> !result.passed()).map(NotebookPodRunner.Result::notebook).toList();
            if (!failed.isEmpty()) {
                System.err.printf("Notebooks failed (%d of %d): %s%n", failed.size(), outcomes.size(), String.join(" ", failed));
                System.exit(1);
            }
            System.out.printf("All notebooks (%d) passed%n", outcomes.size());
        }
    }
}
