package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * Resolves, and reaches, domain names from the cluster pod, whose lookups go through the cluster's DNS and the cloud's resolver.
 *
 * <p>A domain resolves from outside the cluster as soon as its record exists, but if a pod looked it up before then, the failure is cached
 * by CoreDNS and by the cloud's resolver, for up to the SOA minimum of the zone, eg 15 minutes on a Route 53 zone with the
 * default SOA.  Until it expires, pods keep failing with {@code UnknownHostException}, though the domain is fine, and so does
 * whatever calls it first, eg Trino and Polaris fetching a token from KeyCloak.  {@link #waitUntilReachable} waits for that.
 */
public class DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(DnsResolver.class);

    /**
     * Probes in a row that a domain must pass: the cluster's DNS has several replicas, which may not all have the failure cached, so
     * one answer is not enough
     */
    public static final int      CONSECUTIVE = 5;
    public static final Duration PAUSE       = Duration.ofSeconds(3);

    private final KubeClient client;
    private final String     pod;

    /**
     * @param pod the pod to resolve from, which must have curl
     */
    public DnsResolver(KubeClient client, String pod) {
        this.client = client;
        this.pod    = pod;
    }

    /**
     * Any HTTP response will do: the domain may answer 403 to a caller outside its allowed addresses, or 404.  What is wanted is that
     * the name resolves and the load balancer answers.
     *
     * @return the outcome of calling the domain from the pod, a success if it answered
     */
    public ExecResult reach(String domain) {
        try {
            return client.exec(pod, "curl", "-s", "-o", "/dev/null", "--connect-timeout", "3", "--max-time", "5",
                               "https://" + domain + "/");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reaching " + domain, e);
        } catch (Exception e) {
            return new ExecResult(-1, "", e.toString(), "curl https://" + domain + "/", pod);
        }
    }

    /**
     * Waits until the cluster reaches every domain {@value #CONSECUTIVE} times in a row, bounded by the timeout.
     *
     * @return the domains still not reached, each with its last failure, none if all are
     */
    public Map<String, ExecResult> waitUntilReachable(List<String> domains, Duration timeout) {
        return waitUntilReachable(domains, this::reach, timeout, PAUSE, CONSECUTIVE, DnsResolver::sleep);
    }

    /**
     * What to look at when the cluster does not reach a domain, which is gone by the time anyone looks: what the pod resolves the domains
     * to, with the resolver it uses, and the state and latest logs of the cluster's DNS (CoreDNS), whose replicas may differ.  Best
     * effort: what cannot be read is said so, and nothing is thrown.
     *
     * @return the report, in lines
     */
    public String diagnose(List<String> domains) {
        StringBuilder report = new StringBuilder("DNS of the cluster, from ").append(pod).append(":");
        String script = "cat /etc/resolv.conf; for d in " + String.join(" ", domains) + "; do echo \"== $d\"; "
                        + "getent ahosts $d || echo \"getent: no answer\"; "
                        + "if command -v dig > /dev/null; then dig +noall +answer +comments $d; fi; done";
        try {
            ExecResult lookups = client.exec(pod, "sh", "-c", script);
            report.append(String.format("%n%s%s", lookups.stdout().strip(), lookups.stderr().isBlank() ? "" : "\n" + lookups.stderr().strip()));
        } catch (Exception e) {
            report.append(String.format("%n  could not look the domains up from the pod: %s", e));
        }
        try {
            CoreV1Api api  = new CoreV1Api();
            var       dns  = api.listNamespacedPod("kube-system").labelSelector("k8s-app=kube-dns").execute().getItems();
            report.append(String.format("%nCoreDNS: %d pods", dns.size()));
            for (V1Pod dnsPod : dns) {
                String name     = dnsPod.getMetadata().getName();
                int    restarts = dnsPod.getStatus() == null || dnsPod.getStatus().getContainerStatuses() == null ? 0
                                  : dnsPod.getStatus().getContainerStatuses().stream().mapToInt(V1ContainerStatus::getRestartCount).sum();
                report.append(String.format("%n  %s: %s, %d restarts", name, dnsPod.getStatus() == null ? "unknown" : dnsPod.getStatus().getPhase(), restarts));
                try {
                    String logs = api.readNamespacedPodLog(name, "kube-system").tailLines(20).execute();
                    report.append(String.format("%n    last log lines:%n%s", logs.strip().indent(6).stripTrailing()));
                } catch (Exception e) {
                    report.append(String.format("%n    no logs: %s", e.getMessage()));
                }
            }
        } catch (Exception e) {
            report.append(String.format("%nCoreDNS: could not read its pods: %s", e.getMessage()));
        }
        return report.toString();
    }

    /**
     * Probes each domain, pausing between rounds, until each has passed {@code consecutive} times in a row, or the timeout has passed.
     * A failure starts a domain's count again.  A domain that has passed is not probed again.
     *
     * @param probe   tries to reach a domain from inside the cluster
     * @param sleeper sleeps for the given milliseconds
     * @return the domains still not reached, each with the last failure, none if all are
     */
    static Map<String, ExecResult> waitUntilReachable(List<String> domains, Function<String, ExecResult> probe, Duration timeout,
                                                      Duration pause, int consecutive, LongConsumer sleeper) {
        Map<String, Integer>    passes  = new LinkedHashMap<>();
        Map<String, ExecResult> failing = new LinkedHashMap<>();
        domains.forEach(domain -> passes.put(domain, 0));
        long waitedMs = 0;
        while (true) {
            for (String domain : List.copyOf(passes.keySet())) {
                ExecResult result = probe.apply(domain);
                if (result.isSuccess()) {
                    failing.remove(domain);
                    if (passes.merge(domain, 1, Integer::sum) >= consecutive) {
                        passes.remove(domain);
                        logger.info("{} is reachable from the cluster{}", domain, waitedMs > 0 ? " after " + waitedMs / 1000 + "s" : "");
                    }
                } else {
                    passes.put(domain, 0);
                    failing.put(domain, result);
                }
            }
            if (passes.isEmpty()) {
                return Map.of();
            }
            if (waitedMs + pause.toMillis() > timeout.toMillis()) {
                // a domain that passes only now and then has no failure of its own to report
                Map<String, ExecResult> unreached = new LinkedHashMap<>();
                passes.keySet().forEach(domain -> unreached.put(domain, failing.getOrDefault(
                    domain, new ExecResult(-1, "", "answered, but not " + consecutive + " times in a row", "curl https://" + domain + "/", "the cluster"))));
                return unreached;
            }
            if (!failing.isEmpty()) {
                logger.warn("Not yet reachable from the cluster after {}s: {}", waitedMs / 1000, failing.keySet());
            }
            sleeper.accept(pause.toMillis());
            waitedMs += pause.toMillis();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the cluster's DNS", e);
        }
    }
}
