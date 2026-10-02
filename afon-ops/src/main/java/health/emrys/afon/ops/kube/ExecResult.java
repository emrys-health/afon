package health.emrys.afon.ops.kube;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of running a command inside a pod's container, as with {@code kubectl exec}.
 */
public record ExecResult(int exitCode, String stdout, String stderr, String command) {

    public static @NonNull String join(List<ExecResult> results) {
        String total = results.stream()
                .map(error -> error + "\n")
                .collect(Collectors.joining());
        return total;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
