package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.commands.PodCommand;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;

public class GitSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(GitSmokeMain.class);

    public static void main(String[] args) {
        GitSmokeMain         app        = new GitSmokeMain(smokeTestProperties());
        try {
            List<String> errors = app.checkGit();
            if (errors.isEmpty()) {
                logger.info("Smoke test passes");
            } else {
                String errorMsg = String.join("\n", errors);
                logger.error("Errors:\n{}", errorMsg);
                throw new RuntimeException(errorMsg);
            }
        } catch (Exception e) {
            logger.error("Trouble at t' mill", e);
            throw new RuntimeException(e);
        }
    }


    static final String GITEA     = "gitea-http:3000";
    static final String TEST_REPO = "test-repo";

    private final AfonPropertyProvider properties;

    public GitSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private List<String> checkGit() throws Exception {
        List<String> errors = new ArrayList<>();
        try (KubeClient client = new KubeClient(properties)) {
            JupyterHubSpawner spawner    = new JupyterHubSpawner(client);
            String            jupyterPod = spawner.spawnWithJupyterToken(properties);
            String            user       = properties.getValue(AfonProperty.USER_ID);
            String            password   = spawner.propertyOf(user, JupyterHubSpawner::toPassword);

            errors.addAll(errorsWhenRunning(jupyterPod, client, user, password, "Repository not found"));
            errors.addAll(errorsWhenRunning(jupyterPod, client, user, password + "X", "Failed to authenticate user"));
            errors.addAll(pullOrCreateTestRepo(jupyterPod, client, user, password));
        }
        return errors;
    }

    private @NonNull List<String> errorsWhenRunning(String jupyterPod,
                                                    KubeClient client,
                                                    String user,
                                                    String password,
                                                    String expected) throws Exception {
        List<String> errors = new ArrayList<>();
        ExecResult authenticatedRead = checkGitAccessFromJupyterPod(jupyterPod, client, user, password);
        if (!authenticatedRead.stderr().contains(expected)) {
            errors.add(format("Was expecting '%s' but got '%s'", expected, authenticatedRead.stderr()));
        }
        return errors;
    }

    /**
     * Pulls {@link #TEST_REPO} into the Jupyter pod, cloning it first if it is not there yet, and creating it in Gitea
     * for the user if Gitea does not have it.
     */
    private @NonNull List<String> pullOrCreateTestRepo(String jupyterPod,
                                                       KubeClient client,
                                                       String user,
                                                       String password) throws Exception {
        List<String> errors  = new ArrayList<>();
        String       repoUrl = format("http://%s:%s@%s/%s/%s.git", user, password, GITEA, user, TEST_REPO);

        ExecResult lsRemote = client.exec(jupyterPod, "git", "ls-remote", repoUrl);
        if (!lsRemote.isSuccess()) {
            if (!lsRemote.stderr().contains("Repository not found")) {
                errors.add(format("Could not reach %s: %s", TEST_REPO, lsRemote.stderr()));
                return errors;
            }
            logger.info("{} does not exist for {}, so creating it", TEST_REPO, user);
            ExecResult created = client.exec(jupyterPod,
                                             "wget", "-qO-", "--auth-no-challenge",
                                             "--user=" + user, "--password=" + password,
                                             "--header=Content-Type: application/json",
                                             format("--post-data={\"name\":\"%s\",\"auto_init\":true}", TEST_REPO),
                                             format("http://%s/api/v1/user/repos", GITEA));
            if (!created.isSuccess()) {
                errors.add(format("Could not create %s: %s", TEST_REPO, created.stderr()));
                return errors;
            }
        }

        boolean    cloned = client.exec(jupyterPod, "test", "-d", TEST_REPO + "/.git").isSuccess();
        ExecResult pulled = cloned
            ? client.exec(jupyterPod, "git", "-C", TEST_REPO, "pull", repoUrl)
            : client.exec(jupyterPod, "git", "clone", repoUrl, TEST_REPO);
        if (!pulled.isSuccess()) {
            errors.add(format("Could not pull %s: %s", TEST_REPO, pulled.stderr()));
        }
        return errors;
    }

    private ExecResult checkGitAccessFromJupyterPod(String podName,
                                                          KubeClient client,
                                                          String user,
                                                          String password) throws Exception {

        String commandLine = format("git ls-remote http://%s:%s@%s/phil/bogus_repository.git", user, password, GITEA);
        PodCommand command = new PodCommand(client,
                                            podName,
                                            commandLine);
        return command.run().get(0);
    }

}
