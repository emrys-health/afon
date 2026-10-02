package health.emrys.afon.ops.kube.commands;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurlCommand extends CliCommand {
    static final Logger logger = LoggerFactory.getLogger(CurlCommand.class);

    private final KubeClient client;
    private final String     pod;
    private final boolean    expectHttp;

    public CurlCommand(KubeClient client, String pod, String domain, boolean expectHttp) {
        super(client, "curl -I --connect-timeout 3 --max-time 3 https://" + domain);
        this.client     = client;
        this.pod        = pod;
        this.expectHttp = expectHttp;
        logger.info("Checking domain {}", domain);
    }

    /**
     * @return the result of the check, which is a success if curl got an HTTP response just when one is expected,
     * whatever curl's own exit code, eg 6 (could not resolve host) for a domain expected to be unreachable
     */
    @Override
    ExecResult run(String[] command) throws Exception {
        ExecResult result  = client.exec(pod, command);
        if (!result.isSuccess()) { // might be intentional
            logger.warn("Problem running {} on {}. Error: {}", command, pod, result.stderr());
        }
        if (result.stdout().startsWith("HTTP") == expectHttp) {
            return new ExecResult(0, result.stdout(), result.stderr(), result.command());
        }
        logger.info("Unexpected response. Stdout:\n{}\nStderr:{}", result.stdout(), result.stderr());
        return new ExecResult(result.isSuccess() ? -1 : result.exitCode(), result.stdout(), result.stderr(), result.command());
    }

}
