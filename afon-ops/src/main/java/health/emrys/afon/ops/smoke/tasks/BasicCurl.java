package health.emrys.afon.ops.smoke.tasks;

import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.ApiException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class BasicCurl {
    static final Logger logger = LoggerFactory.getLogger(BasicCurl.class);

    private final KubeClient client;
    private final String     pod;
    private final String     domain;
    private final boolean    expectHttp;

    public BasicCurl(KubeClient client, String pod, String domain, boolean expectHttp) {
        this.client     = client;
        this.pod        = pod;
        this.domain     = domain;
        this.expectHttp = expectHttp;
    }

    public List<ExecResult> run() throws Exception {
        ExecResult       result = curl(client, pod, domain);
        List<ExecResult> errors = new ArrayList<>();
        if (result.stdout().startsWith("HTTP") != expectHttp) {
            logger.info("Unexpected response. Stdout:\n{}\nStderr:{}", result.stdout(), result.stderr());
            errors.add(result);
        }
        return errors;
    }

    private @NonNull ExecResult curl(KubeClient client, String pod, String domain) throws ApiException, IOException, InterruptedException, TimeoutException {
        logger.info("Checking domain {}", domain);
        String     command = "curl -I --connect-timeout 3 --max-time 3 https://" + domain;
        ExecResult result  = client.exec(pod, command.split(" "));
        if (!result.isSuccess()) { // might be intentional
            logger.warn("Problem running {} on {}. Error: {}", command, pod, result.stderr());
        }
        return result;
    }
}
