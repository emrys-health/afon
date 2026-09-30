package health.emrys.afon.ops.smoke.tasks;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.ApiException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class PodCommand extends CliCommand {
    private final String pod;

    public PodCommand(KubeClient client, String pod, String... commands) {
        super(client, commands);
        this.pod = pod;
    }

    @Override
    ExecResult run(String command) throws IOException, InterruptedException, ApiException, TimeoutException {
        return client.exec(pod, command.split("\\s+"));
    }
}
