package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.commands.PodCommand;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

public class GitSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(GitSmokeMain.class);

    public static void main(String[] args) {
        AfonPropertyProvider properties = new FilePropertyProvider();
        GitSmokeMain         app        = new GitSmokeMain(properties);
        try {
            List<String> errors = app.checkGit();
            if (!errors.isEmpty()) {
                String errorMsg = String.join("\n", errors);
                logger.error("Errors:\n{}", errorMsg);
                throw new RuntimeException(errorMsg);
            }
        } catch (Exception e) {
            logger.error("Trouble at t' mill", e);
            throw new RuntimeException(e);
        }
    }


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

    private ExecResult checkGitAccessFromJupyterPod(String podName,
                                                          KubeClient client,
                                                          String user,
                                                          String password) throws Exception {

        String commandLine = format("git ls-remote http://%s:%s@gitea-http:3000/phil/bogus_repository.git", user, password);
        PodCommand command = new PodCommand(client,
                                            podName,
                                            commandLine);
        return command.run().get(0);
    }

}
