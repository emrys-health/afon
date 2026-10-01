package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;

import static health.emrys.afon.ops.smoke.SmokeUtils.KUBERNETES_NAMESPACE;

/**
 * Runs an arbitrary command in a pod, like {@code kubectl exec}. Usage:
 * <pre>
 *   KubeExecSmoke [-n namespace] [-c container] [-t timeoutSeconds] pod -- command [args...]
 * </pre>
 * Exits with the remote command's exit code.
 * Eg
 * <pre>
 *   KubeExecSmoke  spark-connector-0-driver -- curl --connect-timeout 3 https://tvsafonstorageaccount.blob.core.windows.net
 * </pre>
 */
public class KubeExecSmoke {

    private static final Logger logger = LoggerFactory.getLogger(KubeExecSmoke.class);

    private static final String USAGE =
            "Usage: KubeExecSmoke [-n namespace] [-c container] [-t timeoutSeconds] <pod> -- <command> [args...]";

    private final KubeClient client;

    public KubeExecSmoke(KubeClient client) {
        this.client = client;
    }

    public ExecResult run(String namespace, String pod, String container, Duration timeout, String... command)
            throws Exception {
        ExecResult result = client.exec(namespace, pod, container, timeout, command);
        System.out.print(result.stdout());
        System.err.print(result.stderr());
        System.out.printf("Exit code: %d%n", result.exitCode());
        return result;
    }

    public static void main(String[] args) throws Exception {
        String namespace = KUBERNETES_NAMESPACE;
        String container = null;
        Duration timeout = Duration.ofMinutes(5);
        String pod = null;

        int i = 0;
        for (; i < args.length && !args[i].equals("--"); i++) {
            switch (args[i]) {
                case "-n", "--namespace" -> namespace = requireValue(args, ++i);
                case "-c", "--container" -> container = requireValue(args, ++i);
                case "-t", "--timeout" -> timeout = Duration.ofSeconds(Long.parseLong(requireValue(args, ++i)));
                default -> {
                    if (pod != null) {
                        throw new IllegalArgumentException("Unexpected argument '" + args[i] + "'. " + USAGE);
                    }
                    pod = args[i];
                }
            }
        }
        String[] command = i < args.length ? Arrays.copyOfRange(args, i + 1, args.length) : new String[0];
        if (pod == null || command.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }

        int exitCode;
        try (KubeClient client = new KubeClient(new OpsPropertyProvider())) {
            ExecResult result = new KubeExecSmoke(client).run(namespace, pod, container, timeout, command);
            logger.info("Output:\n{}\nError:\n{}", result.stdout(), result.stderr());
            exitCode = result.exitCode();
        }
        System.exit(exitCode);
    }

    private static String requireValue(String[] args, int index) {
        if (index >= args.length || args[index].equals("--")) {
            throw new IllegalArgumentException("Missing value for " + args[index - 1] + ". " + USAGE);
        }
        return args[index];
    }
}
