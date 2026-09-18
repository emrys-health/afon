package health.emrys.afon.ops.kube;

/**
 * The outcome of running a command inside a pod's container, as with {@code kubectl exec}.
 */
public record ExecResult(int exitCode, String stdout, String stderr) {

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
