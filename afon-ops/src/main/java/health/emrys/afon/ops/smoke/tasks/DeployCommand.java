package health.emrys.afon.ops.smoke.tasks;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.ApiException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static health.emrys.afon.ops.smoke.SmokeUtils.KUBERNETES_NAMESPACE;

public class DeployCommand extends CliCommand {
    private final String deployment;
    private final String container;

    public DeployCommand(KubeClient client,
                         String deployment,
                         String container,
                         String... commands) {
        super(client, commands);
        this.deployment = deployment;
        this.container  = container;
    }

    @Override
    ExecResult run(String[] command) throws IOException, InterruptedException, ApiException, TimeoutException {
        return client.execInDeployment(KUBERNETES_NAMESPACE, deployment, container, command);
    }
}
