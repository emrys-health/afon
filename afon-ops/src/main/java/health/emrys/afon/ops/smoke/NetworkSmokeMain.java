package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.AllowedCidrs;
import health.emrys.afon.cdk.configs.GuacamoleConfig;
import health.emrys.afon.cdk.configs.JupyterHubConfig;
import health.emrys.afon.cdk.configs.SparkNames;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.commands.CurlCommand;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import health.emrys.afon.rest.HttpResponses;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Service;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.configs.PolarisConfig.DB_PORT;
import static health.emrys.afon.config.AfonCloudProperty.KEYCLOAK_ALLOWED_CIDRS;
import static health.emrys.afon.config.AfonCloudProperty.KEYCLOAK_EXTERNAL;
import static health.emrys.afon.cdk.configs.SparkWorkloads.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static health.emrys.afon.polaris.config.PolarisEndpoint.getPolarisEndpointFor;
import static java.lang.String.format;

public class NetworkSmokeMain {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmokeMain.class);
    private final AfonPropertyProvider properties;
    private final KubeClient           client;
    private final Set<String>          allowedIngressHosts;

    private NetworkSmokeMain(AfonPropertyProvider properties, KubeClient client) {
        this.properties          = properties;
        this.client              = client;
        this.allowedIngressHosts = Set.of(
                                          AWS_JUPYTER_DOMAIN_NAME, AZURE_JUPYTER_DOMAIN_NAME, GCP_JUPYTER_DOMAIN_NAME,
                                          AWS_OIDC_DOMAIN_NAME, AZURE_OIDC_DOMAIN_NAME, GCP_OIDC_DOMAIN_NAME,
                                          AWS_POLARIS_DOMAIN_NAME, AZURE_POLARIS_DOMAIN_NAME, GCP_POLARIS_DOMAIN_NAME,
                                          OIDC_DOMAIN_NAME)
                                      .stream()
                                      .map(x -> properties.getValue(x, ""))
                                      .filter(x -> !x.isEmpty())
                                      .collect(Collectors.toUnmodifiableSet());
    }

    interface Check {
        List<ExecResult> check(String pod) throws Exception;
    }

    public List<ExecResult> checkNetworkWithPods() throws Exception {
        List<ExecResult> results = new ArrayList<>();
        results.addAll(checkKeyCloakAdminOnlyAccessibleFromAllowedCidrs());
        results.addAll(checkPolarisCanTalkToDB());
        results.addAll(checkPublicFacingInterfaces());
        results.addAll(checkPolarisOnlyAccessibleFromCluster());
        results.addAll(checkGuacamoleOnlyReachableFromOauth2Proxy());
        // Only the driver and the workers call what the ingress exposes, so only they may reach the ingress controller, when it is in
        // the cluster: the master is allowed the other domains, but not those, so it is not checked for the domains
        results.addAll(checkNetworkWithPods(
            sparkPods(pod -> !isSparkMaster(pod)), List.of(
                this::checkCanSeeAllowedDomains,
                this::checkCannotSeeOtherBuckets)));
        results.addAll(checkNetworkWithPods(
            sparkPods(this::isSparkMaster), List.of(
                this::checkCannotSeeOtherBuckets)));
        results.addAll(checkNetworkWithPods(jupyterPods(), List.of(
            this::checkCannotSeeOtherBuckets,
            this::checkVncPasswordIsNotShared,
            this::checkNotebookOnlyReachableFromHubAndGuacamole)));
        return results;
    }

    /**
     * Checks that a pod other than oauth2-proxy cannot reach Guacamole, which logs in whoever the {@code X-Forwarded-Email}
     * header names.  From Polaris, which no egress policy limits, so that only Guacamole's own ingress policy can stop it.
     */
    private List<ExecResult> checkGuacamoleOnlyReachableFromOauth2Proxy() throws Exception {
        String polarisPodName = KubeClient.nameOfPod(client.getOnlyPodBegginingWith("polaris-deployment"));
        logger.info("Checking Guacamole cannot be reached from {}", polarisPodName);
        return new CurlCommand(client, polarisPodName, GuacamoleConfig.SVC_NAME + ":" + GuacamoleConfig.WEB_PORT, false).run();
    }

    public List<ExecResult> checkPolarisCanTalkToDB() throws Exception {
        String polarisPodName = KubeClient.nameOfPod(client.getOnlyPodBegginingWith("polaris-deployment"));
        CurlCommand command = new CurlCommand(
            client,
            polarisPodName,
            properties.getValue(POLARIS_DB_HOST) + ":" + DB_PORT,
            false);

        return command.run().stream().map(x -> {
            if (x.stderr().contains("no application protocol") || x.stderr().contains("Empty reply from server")) {
                return x.makeSuccessful(); // this is a good error!
            }
            return x.makeFailed();
        }).toList();
    }

    /**
     * Stands for every host, which an ingress serves when a rule of it names none, or when it has no rules.
     */
    static final String ANY_HOST = "*";

    /**
     * Checks that no service of the cluster has an EXTERNAL-IP, as {@code kubectl get services} shows it, ie that none
     * is exposed outside the cluster other than through an ingress, and that the ingresses serve only
     * {@link #allowedIngressHosts}.
     */
    private List<ExecResult> checkPublicFacingInterfaces() throws ApiException {
        List<V1Service> services = client.listServices();
        logger.info("Checking services have no EXTERNAL-IP: {}", services.stream().map(s -> s.getMetadata().getName()).toList());
        List<ExecResult> results   = new ArrayList<>(publicFacingInterfaces(services));
        List<V1Ingress>  ingresses = client.listIngresses();
        logger.info(
            "Checking ingresses serve only {}: {}", allowedIngressHosts,
            ingresses.stream().map(i -> i.getMetadata().getName()).toList());
        results.addAll(ingressHosts(ingresses, allowedIngressHosts));
        return results;
    }

    /**
     * @return for each ingress, a failure if it serves a host that is not allowed, else a success
     */
    static List<ExecResult> ingressHosts(List<V1Ingress> ingresses, Set<String> allowed) {
        return ingresses.stream().map(ingress -> {
            String       name       = ingress.getMetadata().getNamespace() + "/" + ingress.getMetadata().getName();
            List<String> notAllowed = hostsOf(ingress).stream().filter(host -> !allowed.contains(host)).toList();
            String command = format(
                "kubectl get ingress -n %s %s", ingress.getMetadata().getNamespace(),
                ingress.getMetadata().getName());
            logger.info("Checking ingress: {}", name);
            return notAllowed.isEmpty()
                   ? new ExecResult(0, format("Ingress %s serves only allowed hosts", name), "", command, "localhost")
                   : new ExecResult(
                -1, "", format(
                "Ingress %s serves hosts that are not allowed: %s",
                name, String.join(",", notAllowed)), command, "localhost");
        }).toList();
    }

    /**
     * @return the hosts of the ingress' rules, {@link #ANY_HOST} for a rule without one, or for an ingress without rules
     */
    static List<String> hostsOf(V1Ingress ingress) {
        List<V1IngressRule> rules = ingress.getSpec() == null ? null : ingress.getSpec().getRules();
        if (rules == null || rules.isEmpty()) {
            return List.of(ANY_HOST);
        }
        return rules.stream()
                    .map(rule -> rule.getHost() == null || rule.getHost().isBlank() ? ANY_HOST : rule.getHost())
                    .distinct()
                    .toList();
    }

    /**
     * @return for each service, a failure if it has an EXTERNAL-IP, else a success
     */
    static List<ExecResult> publicFacingInterfaces(List<V1Service> services) {
        return services.stream().map(service -> {
            String       name     = service.getMetadata().getName();
            List<String> external = externalIps(service);
            String       command  = format("kubectl get service %s", name);
            return external.isEmpty()
                   ? new ExecResult(0, format("Service %s has no EXTERNAL-IP", name), "", command, "localhost")
                   : new ExecResult(-1, "", format("Service %s has EXTERNAL-IP %s", name, String.join(",", external)), command, "localhost");
        }).toList();
    }

    /**
     * What {@code kubectl} shows as a service's EXTERNAL-IP: the addresses, or host names, of its load balancer, and any
     * external IPs it is given.  A load balancer still {@code <pending>} has none yet.
     */
    static List<String> externalIps(V1Service service) {
        List<String> ips = new ArrayList<>();
        if (service.getStatus() != null && service.getStatus().getLoadBalancer() != null
            && service.getStatus().getLoadBalancer().getIngress() != null) {
            for (V1LoadBalancerIngress ingress : service.getStatus().getLoadBalancer().getIngress()) {
                if (ingress.getIp() != null && !ingress.getIp().isBlank()) {
                    ips.add(ingress.getIp());
                }
                if (ingress.getHostname() != null && !ingress.getHostname().isBlank()) {
                    ips.add(ingress.getHostname());
                }
            }
        }
        if (service.getSpec() != null && service.getSpec().getExternalIPs() != null) {
            ips.addAll(service.getSpec().getExternalIPs());
        }
        return ips;
    }

    /**
     * Calls Polaris from outside the cluster, which must not get through.  How a caller outside of the allowed CIDRs is
     * stopped differs per cloud:
     * <ul>
     *   <li>AWS: the ALB's security group drops its packets, so the call fails or times out, and any HTTP response
     *   means Polaris is reachable.</li>
     *   <li>Azure: the nginx ingress controller, which runs in the cluster behind a public load balancer, accepts the
     *   connection and answers with its own 403 page, without calling Polaris, see
     *   {@link SmokeConfig#rejectedByIngress}.  That counts as stopped; any other response means Polaris is reachable.</li>
     * </ul>
     */
    private List<ExecResult> checkPolarisOnlyAccessibleFromCluster() throws Exception {
        SmokeConfig       smokeConfig   = new SmokeConfig(properties);
        AfonCloud         cloud         = smokeConfig.cloud();
        String            polarisHost   = getPolarisEndpointFor(cloud, properties);
        PolarisRestClient polarisClient = PolarisRestClientFactory.clientFactory().createClient(properties.forCloud(cloud), polarisHost);
        List<ExecResult>  results       = new ArrayList<>();
        try {
            logger.warn(
                "About to call {}. Note that if whitelisting is turned on, this can take up to {}s",
                polarisHost, smokeConfig.callTimeoutSeconds());
            HttpResponse response = getWithProgress(polarisClient, polarisHost, smokeConfig);
            int          httpCode = response.getCode(); // probably a 403 or 404 but that's not the point!
            if (smokeConfig.rejectedByIngress(httpCode, HttpResponses.contentOf(response))) {
                String msg = format("The ingress of %s rejected the call with HTTP code %d, as it should", polarisHost, httpCode);
                logger.info(msg);
                results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost), "localhost"));
                return results;
            }
            String message = format(
                "Managed to make an HTTP call to %s. Received HTTP code %d.%n%n%s",
                polarisHost, httpCode, smokeConfig.polarisRestrictionAdvice());
            results.add(new ExecResult(-1, message, "", format("curl https://%s", polarisHost), "localhost"));
        } catch (IOException x) {
            String msg = format("Was unable to talk to %s. Error = %s", polarisHost, x.getMessage());
            logger.info(msg);
            results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost), "localhost"));
        }
        return results;
    }

    /**
     * A user that KeyCloak does not know, whose token request it can only refuse, with a 401: an answer of KeyCloak's own.
     */
    static final String UNKNOWN_KEYCLOAK_USER = "network-smoke-test-unknown-user";

    /**
     * The HTTP code of a refused token request, as {@link HttpResponses#parseAccessToken} words it.
     */
    private static final Pattern TOKEN_REFUSAL_CODE = Pattern.compile("code: (\\d+)");

    /**
     * Calls KeyCloak from outside the cluster, ie from here, through {@link KeyCloakProvider}, when {@code
     * <CLOUD>_KEYCLOAK_ALLOWED_CIDRS} restricts it: its admin side must refuse this machine, which is not expected to be among
     * the allowed CIDRs, while its login side, the {@code polaris} realm, stays open to all.
     * <ul>
     *   <li>The admin side, by asking the {@code master} realm for a token as the bootstrap admin, which the ingress must answer
     *   with its own 403: the AWS ALB's fixed response, or nginx's page on Azure and GCP. KeyCloak itself never answers a token
     *   request so, so any other answer, a token above all, means that this machine reached the admin side.</li>
     *   <li>The login side, by asking the {@code polaris} realm for a token as {@link #UNKNOWN_KEYCLOAK_USER}, which KeyCloak must
     *   refuse itself, ie a response other than a 403, as anyone may log in from anywhere.</li>
     * </ul>
     * Not checked when KeyCloak is not restricted, or is another stack's, with {@code <CLOUD>_KEYCLOAK_EXTERNAL=true}, so whose
     * ingress is not this stack's.
     */
    private List<ExecResult> checkKeyCloakAdminOnlyAccessibleFromAllowedCidrs() {
        AfonCloud            cloud           = new SmokeConfig(properties).cloud();
        AfonPropertyProvider cloudProperties = properties.forCloud(cloud);
        String               host            = cloudProperties.getValue(OIDC_DOMAIN_NAME);
        String               command         = format("POST https://%s/realms/{master,polaris}/protocol/openid-connect/token", host);
        if (Boolean.parseBoolean(properties.getCloudValue(cloud, KEYCLOAK_EXTERNAL, "false").trim())) {
            return List.of(new ExecResult(
                0, format("Not checked: the KeyCloak at %s is another stack's, as %s_KEYCLOAK_EXTERNAL=true", host,
                          cloud.name()), "", command, "localhost"));
        }
        if (AllowedCidrs.of(properties, cloud, "keycloak").isEmpty()) {
            return List.of(new ExecResult(
                0, format("Not checked: the KeyCloak at %s is open to all, as %s is not set", host,
                          KEYCLOAK_ALLOWED_CIDRS.forCloud(cloud).map(Enum::name).orElse("its allowed CIDRs")), "", command,
                "localhost"));
        }
        KeyCloakProvider keyCloak = new KeyCloakProvider(cloudProperties);
        logger.info("Checking that the admin side of the KeyCloak at {} refuses this machine, while its login side admits it", host);
        return List.of(
            adminSideRefused(host, keyCloak::adminToken),
            loginSideOpen(host, () -> keyCloak.userToken(UNKNOWN_KEYCLOAK_USER, "not-a-password")));
    }

    /**
     * @param adminToken asks the {@code master} realm for a token as the bootstrap admin
     * @return a success if the ingress refused the request with a 403, else a failure
     */
    static ExecResult adminSideRefused(String host, Callable<String> adminToken) {
        String command = format("POST https://%s/realms/master/protocol/openid-connect/token", host);
        String error;
        try {
            adminToken.call();
            error = format("Obtained an admin token from the KeyCloak at %s from here, which is not meant to be allowed to reach its "
                           + "admin side", host);
        } catch (Exception e) {
            int code = refusalCodeOf(e);
            if (code == 403) {
                String msg = format("The ingress of the KeyCloak at %s refused an admin token request from here with HTTP 403, as it "
                                    + "should", host);
                logger.info(msg);
                return new ExecResult(0, msg, "", command, "localhost");
            }
            error = code > 0
                    ? format("The KeyCloak at %s answered an admin token request from here with HTTP %d rather than its ingress refusing "
                             + "it with 403, so its admin side is reachable from here: %s", host, code, e.getMessage())
                    : format("Could not tell whether the admin side of the KeyCloak at %s is reachable from here: %s", host,
                             e.getMessage());
        }
        return new ExecResult(-1, "", error + adminRestrictionAdvice(), command, "localhost");
    }

    /**
     * @param userToken asks the {@code polaris} realm for a token as {@link #UNKNOWN_KEYCLOAK_USER}
     * @return a success if KeyCloak itself refused the request, ie with a code other than 403, else a failure
     */
    static ExecResult loginSideOpen(String host, Callable<String> userToken) {
        String command = format("POST https://%s/realms/polaris/protocol/openid-connect/token", host);
        String error;
        try {
            userToken.call();
            error = format("The KeyCloak at %s gave a token to %s, a user it should not know", host, UNKNOWN_KEYCLOAK_USER);
        } catch (Exception e) {
            int code = refusalCodeOf(e);
            if (code > 0 && code != 403) {
                String msg = format("The login side of the KeyCloak at %s is reachable from here: it refused an unknown user with HTTP %d",
                                    host, code);
                logger.info(msg);
                return new ExecResult(0, msg, "", command, "localhost");
            }
            error = code == 403
                    ? format("The ingress of the KeyCloak at %s refused a login from here with HTTP 403, but logins must be open to all: "
                             + "%s", host, e.getMessage())
                    : format("Could not reach the login side of the KeyCloak at %s from here: %s", host, e.getMessage());
        }
        return new ExecResult(-1, "", error, command, "localhost");
    }

    /**
     * @return the HTTP code of a token request that KeyCloak or its ingress refused, or -1 if it got no response
     */
    static int refusalCodeOf(Exception e) {
        if (e instanceof IllegalStateException && e.getMessage() != null) {
            Matcher matcher = TOKEN_REFUSAL_CODE.matcher(e.getMessage());
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    private static String adminRestrictionAdvice() {
        return format("%n%nCheck that <CLOUD>_KEYCLOAK_ALLOWED_CIDRS does not include this machine (curl -s https://ifconfig.me), that "
                      + "the stack was redeployed since it was set, and what kubectl describe ingress keycloak-ingress says.");
    }

    /**
     * Calls Polaris' root, logging every {@link SmokeConfig#progressIntervalSeconds()} that it is still waiting for a
     * response, as a call from outside the cluster to a restricted Polaris only fails once it times out, and giving up
     * after {@link SmokeConfig#callTimeoutSeconds()}, rather than after the system's own connect timeout, eg 75s per
     * address on macOS.
     *
     * @throws IOException if the call fails, eg as the connection timed out, or gets no response in time
     */
    private HttpResponse getWithProgress(PolarisRestClient polarisClient, String polarisHost, SmokeConfig smokeConfig)
    throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse> response = executor.submit(() -> polarisClient.get("/", "bogus_token"));
            long                 start    = System.nanoTime();
            while (true) {
                try {
                    return response.get(smokeConfig.progressIntervalSeconds(), TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
                    if (elapsed >= smokeConfig.callTimeoutSeconds()) {
                        response.cancel(true);
                        throw new SocketTimeoutException(format("No response within %ds", elapsed));
                    }
                    logger.info("Still waiting for a response from {} after {}s", polarisHost, elapsed);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException cause) {
                        throw cause;
                    }
                    throw e;
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ExecResult> checkNetworkWithPods(List<String> pods, List<Check> checks) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                for (Check check : checks) {
                    results.addAll(check.check(pod));
                }
            } catch (Exception e) {
                logger.error("Could not execute on pod " + pod, e);
                results.add(new ExecResult(-1, "", e.getMessage(), format("checks on pod %s", pod), pod));
            }
        }
        return results;
    }

    protected List<String> sparkPods() throws Exception {
        return sparkPods(pod -> true);
    }

    private List<String> sparkPods(Predicate<String> which) throws Exception {
        SparkNames sparkNames = SparkNames.from(properties);
        List<String> pods = client.listPods().stream().map(x -> x.getMetadata().getName()).filter(name -> {
            return name.contains(sparkNames.master()) ||name.contains(sparkNames.worker()) ||
                name.contains(SPARK_CONNECTOR_NAME);
        }).filter(which).toList();
        logger.info("Checking Spark pods: {}", pods);
        return pods;
    }

    private boolean isSparkMaster(String pod) {
        return pod.contains(SparkNames.from(properties).master());
    }

    protected List<String> jupyterPods() throws Exception {
        List<String> pods = new ArrayList<>();
        pods.add(new JupyterHubSpawner(client).spawnWithJupyterToken(properties));
        logger.info("Checking Jupyter pods: {}", pods);
        return pods;
    }

    private List<ExecResult> checkCannotSeeOtherBuckets(String pod) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        String[] providers = new String[]{
            "unreachablebucket.eu-west-2.amazonaws.com",
            "unreachablebucket.blob.core.windows.net",
            "pypi.io",
            "pypi.org",
            "pypi.org",
            GuacamoleConfig.SVC_NAME + ":" + GuacamoleConfig.WEB_PORT
            };
        for (String provider : providers) {
            // the domains over HTTPS, as a user reaches them; Guacamole is a service of the cluster, over plain HTTP
            CurlCommand command = provider.contains(":") ? new CurlCommand(client, pod, provider, false)
                                                         : CurlCommand.https(client, pod, provider, false);
            results.addAll(command.run());
        }
        return results;
    }

    /**
     * Checks that a pod other than the hub and Guacamole cannot connect to the notebook's VNC server or single-user server.
     * From Polaris, which no egress policy limits, so that only the notebook's own ingress policy can stop it.  By whether a
     * TCP connection was made at all, as VNC answers no HTTP.
     */
    private List<ExecResult> checkNotebookOnlyReachableFromHubAndGuacamole(String pod) throws Exception {
        String polarisPodName = KubeClient.nameOfPod(client.getOnlyPodBegginingWith("polaris-deployment"));
        String ip = client.listPods().stream()
                          .filter(p -> pod.equals(KubeClient.nameOfPod(p)))
                          .map(p -> p.getStatus().getPodIP())
                          .findFirst()
                          .orElseThrow(() -> new IllegalStateException("No IP for pod " + pod));
        List<ExecResult> results = new ArrayList<>();
        for (int port : List.of(JupyterHubConfig.VNC_PORT, JupyterHubConfig.SINGLEUSER_PORT)) {
            String target = format("%s (%s:%d)", pod, ip, port);
            logger.info("Checking {} cannot be reached from {}", target, polarisPodName);
            ExecResult connect = client.exec(
                polarisPodName, "curl", "-s", "-o", "/dev/null", "-w", "%{time_connect}", "--connect-timeout", "3",
                "--max-time", "3", "telnet://" + ip + ":" + port);
            results.add(notConnected(connect, target));
        }
        return results;
    }

    /**
     * @param connect the result of curl writing out {@code %{time_connect}}, which is 0 when no connection was made
     * @return a success if no connection was made to the target, else a failure
     */
    static ExecResult notConnected(ExecResult connect, String target) {
        double secondsToConnect;
        try {
            secondsToConnect = Double.parseDouble(connect.stdout().strip());
        } catch (NumberFormatException e) {
            return new ExecResult(-1, connect.stdout(), format("Could not tell whether %s was reached: %s", target, connect.stderr()),
                                  connect.command(), connect.host());
        }
        return secondsToConnect == 0
               ? new ExecResult(0, format("%s cannot be reached from %s", target, connect.host()), "", connect.command(), connect.host())
               : new ExecResult(-1, "", format("%s can be reached from %s", target, connect.host()), connect.command(), connect.host());
    }

    /**
     * Checks that the pod's VNC server is not behind {@code JUPYTER_VNC_PASSWORD}, which is shared by, ie known to, every
     * user's desktop if it is used as is: each user's pod must have its own password, derived from it.
     */
    private List<ExecResult> checkVncPasswordIsNotShared(String pod) throws Exception {
        ExecResult read = client.exec(pod, "sh", "-c", "base64 \"$HOME/.vnc/passwd\"");
        return List.of(vncPasswordIsNotShared(read, properties.getValue(JUPYTER_VNC_PASSWORD)));
    }

    /**
     * @param read   the result of base64-encoding the pod's {@code ~/.vnc/passwd}
     * @param shared the value of {@code JUPYTER_VNC_PASSWORD}
     * @return a failure if the file could not be read, or holds the shared password, else a success; never with the file's
     * contents, which give away the password
     */
    static ExecResult vncPasswordIsNotShared(ExecResult read, String shared) {
        String command = "base64 ~/.vnc/passwd";
        if (!read.isSuccess()) {
            return new ExecResult(
                -1, "", format("Could not read ~/.vnc/passwd on %s: %s", read.host(), read.stderr()), command, read.host());
        }
        byte[] passwd = Base64.getMimeDecoder().decode(read.stdout().strip());
        if (Arrays.equals(passwd, VncPasswd.encode(shared))) {
            return new ExecResult(
                -1, "", format(
                "The VNC password of %s is JUPYTER_VNC_PASSWORD, which every user's desktop would share, not the user's own",
                read.host()), command, read.host());
        }
        return new ExecResult(
            0, format("The VNC password of %s is not JUPYTER_VNC_PASSWORD", read.host()), "", command, read.host());
    }

    /**
     * Over HTTPS, as a user reaches a domain: not over HTTP, which only redirects to HTTPS, and which a cluster may not allow
     * to an ingress controller in the cluster.
     */
    private List<ExecResult> checkCanSeeAllowedDomains(String pod) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        for (String domain : new SmokeConfig(properties).allowedDomains()) {
            CurlCommand command = CurlCommand.https(client, pod, domain, true);
            results.addAll(leavingOutDownDomain(domain, command.run(), NetworkSmokeMain::resolvesHere));
        }
        return results;
    }

    /**
     * A domain being allowed means that the pod may reach it, not that it answers: it may be down, or not deployed, eg the Keycloak of
     * a cloud other than that of the stack. A pod that gets no response from a domain that this machine cannot resolve either is not
     * stopped by the network policy, so that is not a failure; it is reported as a check not made. Where this machine does resolve
     * it, the pod not getting a response is a failure.
     *
     * @param results  the results of checking that the pod can reach the domain
     * @param resolves whether this machine can resolve the domain
     */
    static List<ExecResult> leavingOutDownDomain(String domain, List<ExecResult> results, Predicate<String> resolves) {
        if (results.stream().allMatch(ExecResult::isSuccess) || resolves.test(domain)) {
            return results;
        }
        logger.warn(
            "Not checking that the pod can reach {}: it does not resolve from here either, so it may be down or not deployed",
            domain);
        return results.stream()
                      .map(result -> result.isSuccess() ? result
                                                        : new ExecResult(
                          0, "Not checked: " + domain + " does not resolve from here either",
                          "", result.command(), result.host()))
                      .toList();
    }

    private static boolean resolvesHere(String domain) {
        try {
            InetAddress.getByName(domain);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        int                  exitCode   = 0;
        AfonPropertyProvider properties = smokeTestProperties();
        try (KubeClient client = new KubeClient(properties)) {
            NetworkSmokeMain app      = new NetworkSmokeMain(properties, client);
            List<ExecResult> results  = app.checkNetworkWithPods();
            List<ExecResult> failures = results.stream().filter(result -> !result.isSuccess()).toList();
            if (!failures.isEmpty()) {
                logger.error("{} of {} network checks failed:\n{}", failures.size(), results.size(), ExecResult.join(failures));
                exitCode = 1;
            } else {
                logger.info("Network smoke test passed: {} checks", results.size());
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

}
