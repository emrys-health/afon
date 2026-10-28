package health.emrys.afon.ops.kube;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of running a command inside a pod's container, as with {@code kubectl exec}.
 */
public record ExecResult(int exitCode, String stdout, String stderr, String command) {

    public static int SUCCESS = 0;
    public static int FAILURE = 1;

    public static @NonNull String join(List<ExecResult> results) {
        String total = results.stream()
                .map(error -> error + "\n")
                .collect(Collectors.joining());
        return total;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public ExecResult makeSuccessful() {
        return new ExecResult(SUCCESS, stdout, stderr, command);
    }

    public ExecResult makeFailed() {
        return new ExecResult(FAILURE, stdout, stderr, command);
    }
}
