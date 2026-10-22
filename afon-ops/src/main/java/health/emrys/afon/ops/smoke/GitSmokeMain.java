package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(GitSmokeMain.class);

    public static void main(String[] args) {
        AfonPropertyProvider properties = new FilePropertyProvider();
        GitSmokeMain         app        = new GitSmokeMain(properties);
        try {
            app.checkGit();
        } catch (Exception e) {
            logger.error("Trouble at t'Git", e);
            throw new RuntimeException(e);
        }
    }


    private final AfonPropertyProvider properties;

    public GitSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void checkGit() throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            String jupyterPod = new JupyterHubSpawner(client).spawnWithJupyterToken(properties);
            checkGitAccessFromJupyterPod(jupyterPod);
        }
    }

    private void checkGitAccessFromJupyterPod(String podName) {

    }

}
