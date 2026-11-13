package health.emrys.afon.ops.kube.commands;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.LongConsumer;

public class CurlCommand extends CliCommand {
    static final Logger logger = LoggerFactory.getLogger(CurlCommand.class);

    /**
     * How long a check that expects an HTTP response keeps trying, as a new pod may fail its first DNS lookups
     */
    static final Duration RETRY_WINDOW = Duration.ofSeconds(30);
    static final Duration RETRY_PAUSE  = Duration.ofSeconds(3);

    /**
     * One attempt of a check, which may throw
     */
    @FunctionalInterface
    interface Attempt {
        ExecResult run() throws Exception;
    }

    private final KubeClient client;
    private final String     pod;
    private final boolean    expectHttp;
    private       boolean    retrying;

    /**
     * Over plain HTTP, as for a service in the cluster.
     */
    public CurlCommand(KubeClient client, String pod, String domain, boolean expectHttp) {
        this(client, pod, "http", domain, expectHttp);
    }

    /**
     * @param scheme {@code http} or {@code https}
     */
    private CurlCommand(KubeClient client, String pod, String scheme, String domain, boolean expectHttp) {
        super(client, "curl -L -I --connect-timeout 3 --max-time 3 " + scheme + "://" + domain);
        this.client     = client;
        this.pod        = pod;
        this.expectHttp = expectHttp;
        this.retrying   = expectHttp;
        logger.info("Checking domain {}", domain);
    }

    /**
     * Over HTTPS, as a user reaches a domain, ie without depending on port 80, which a cluster may not allow, eg where the
     * ingress controller is in the cluster and only its HTTPS port is allowed.
     */
    public static CurlCommand https(KubeClient client, String pod, String domain, boolean expectHttp) {
        return new CurlCommand(client, pod, "https", domain, expectHttp);
    }

    /**
     * For a domain that is expected not to answer, eg one that nothing resolves, where retrying only delays the failure.
     */
    public CurlCommand withoutRetry() {
        this.retrying = false;
        return this;
    }

    /**
     * A check that expects an HTTP response is retried for {@link #RETRY_WINDOW}, as a transient failure, eg a DNS lookup that fails
     * on a pod that has just started, would otherwise fail it.  A check that expects no response is tried once: a domain that is
     * blocked never answers, so a retry cannot hide a leak from it, but retrying could.
     *
     * @return the result of the check, which is a success if curl got an HTTP response just when one is expected,
     * whatever curl's own exit code, eg 6 (could not resolve host) for a domain expected to be unreachable
     */
    @Override
    ExecResult run(String[] command) throws Exception {
        if (!retrying) {
            return attempt(command);
        }
        return retryingUntilSuccess(() -> attempt(command), RETRY_WINDOW, RETRY_PAUSE, CurlCommand::sleep);
    }

    private ExecResult attempt(String[] command) throws Exception {
        ExecResult result  = client.exec(pod, command);
        if (!result.isSuccess()) { // might be intentional
            logger.warn("Problem running {} on {}. Error: {}", command, pod, result.stderr());
        }
        if (result.stdout().startsWith("HTTP") == expectHttp) {
            return new ExecResult(0, result.stdout(), result.stderr(), result.command(), result.host());
        }
        logger.info("Unexpected response. Stdout:\n{}\nStderr:{}", result.stdout(), result.stderr());
        return new ExecResult(result.isSuccess() ? -1 : result.exitCode(), result.stdout(), result.stderr(), result.command(), result.host());
    }

    /**
     * Tries until an attempt succeeds, or the window has passed, pausing between attempts.
     *
     * @param sleeper sleeps for the given milliseconds
     * @return the first success, else the last failure
     */
    static ExecResult retryingUntilSuccess(Attempt attempt, Duration window, Duration pause, LongConsumer sleeper) throws Exception {
        long     waitedMs = 0;
        int      attempts = 1;
        ExecResult result = attempt.run();
        while (!result.isSuccess() && waitedMs + pause.toMillis() <= window.toMillis()) {
            sleeper.accept(pause.toMillis());
            waitedMs += pause.toMillis();
            attempts++;
            result = attempt.run();
        }
        if (result.isSuccess() && attempts > 1) {
            logger.warn("Succeeded only at attempt {}, after {}s: a transient failure?", attempts, waitedMs / 1000);
        }
        return result;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying", e);
        }
    }

}
