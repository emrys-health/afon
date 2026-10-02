package health.emrys.afon.ops.kube.commands;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;

import java.util.ArrayList;
import java.util.List;

abstract public class CliCommand {

    protected final KubeClient client;
    private final   String[]   commands;

    public CliCommand(KubeClient client, String... commands) {
        this.client   = client;
        this.commands = commands;
    }

    public List<ExecResult> run() throws Exception {
        List<ExecResult> results = new ArrayList<>();
        for (String command : commands) {
            results.add(run(command.split("\\s+")));
        }
        return results;
    }

    abstract ExecResult run(String[] command) throws Exception;
}
