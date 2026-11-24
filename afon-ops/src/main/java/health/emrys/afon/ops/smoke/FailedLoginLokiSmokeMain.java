package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.LokiClient;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static health.emrys.afon.config.AfonCloudProperty.JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonCloudProperty.KEYCLOAK_EXTERNAL;
import static health.emrys.afon.ops.kube.tools.LokiClient.withRetries;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;

/**
 * Checks that a failed login at the TRE's Jupyter domain, {@code <CLOUD>_JUPYTER_DOMAIN_NAME}, leaves a trace in the
 * logs that Promtail ships to Loki.
 * <p>
 * As a browser would, it requests a unique path at the Pomerium MFA gate in front of JupyterHub, follows its redirect
 * through {@code auth.<jupyter>} to KeyCloak's login page, and submits that page's form with a username that no user
 * has and a wrong password.  Both are made unique to the run by a marker, so that the logs found are this run's and no
 * other's.  It then checks, in Loki:
 * <ol>
 *     <li>that the gate's pods logged the request that started the login, which carries the marker in its path;</li>
 *     <li>that KeyCloak's pods logged the refused login, the {@code LOGIN_ERROR} event of its {@code jboss-logging}
 *     listener, naming the unknown username.  Not checked when KeyCloak is another stack's, with
 *     {@code <CLOUD>_KEYCLOAK_EXTERNAL=true}, as its logs are not in this cluster's Loki.</li>
 * </ol>
 * It fails, too, if KeyCloak accepts the login, ie redirects back to the gate with an authorization code.
 * <p>
 * No real credentials are used: the username is made up and never exists, so no account can be locked out.
 */
public class FailedLoginLokiSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(FailedLoginLokiSmokeMain.class);

    /** The path of KeyCloak's login form's action, eg {@code /realms/polaris/login-actions/authenticate?session_code=...}. */
    static final String  LOGIN_ACTION     = "login-actions/authenticate";
    /** The event KeyCloak's {@code jboss-logging} listener logs, at WARN, when a login is refused. */
    static final String  LOGIN_ERROR      = "LOGIN_ERROR";
    /** The prefix of the pods of KeyCloak's Deployment, see {@code KeyCloakConfig}. */
    static final String  KEYCLOAK_POD     = "keycloak-";
    private static final Pattern FORM     = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION   = Pattern.compile("\\baction\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    /** Promtail ships every few seconds, and KeyCloak's and the gate's log lines may take a while to reach Loki. */
    private static final int     RETRIES  = 8;

    /** What KeyCloak answered the login form with. */
    record LoginResult(int status, String location) {
        /** KeyCloak accepted the login if it redirected the browser back to the gate with an authorization code. */
        boolean accepted() {
            return status >= 300 && status < 400 && location != null && location.contains("code=");
        }
    }

    private final AfonCloud            cloud;
    private final AfonPropertyProvider properties;

    public FailedLoginLokiSmokeMain(AfonCloud cloud, AfonPropertyProvider properties) {
        this.cloud      = cloud;
        this.properties = properties;
    }

    public static void main(String[] args) throws Exception {
        AfonPropertyProvider properties = smokeTestProperties();
        FailedLoginLokiSmokeMain app = new FailedLoginLokiSmokeMain(new SmokeConfig(properties).cloud(), properties);
        List<String> errors = app.check();
        if (!errors.isEmpty()) {
            String msg = String.join("\n", errors);
            logger.error(msg);
            throw new RuntimeException(msg);
        }
        logger.info("Test passed");
    }

    /**
     * @return the errors, if any, of the failed login not being refused, or not being in Loki
     */
    public List<String> check() throws Exception {
        String host     = properties.getCloudValue(cloud, JUPYTER_DOMAIN_NAME);
        if (host == null || host.isBlank()) {
            return List.of(format("%s_JUPYTER_DOMAIN_NAME is not set, so there is no Jupyter to log in to", cloud.name()));
        }
        String marker   = "smoke-" + UUID.randomUUID().toString().substring(0, 12);
        String username = "no-such-user-" + marker;
        // a minute back, so that the clocks of this machine and of the cluster need not agree to the second
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(1);

        List<String> errors = new ArrayList<>();
        LoginResult  result = attemptLogin(host.trim(), marker, username);
        if (result.accepted()) {
            errors.add(format("KeyCloak accepted the login of the unknown user %s at %s, redirecting to %s", username, host, result.location()));
        } else {
            logger.info("KeyCloak refused the login of {} with HTTP {}", username, result.status());
        }

        try (KubeClient client = new KubeClient(properties);
             LokiClient loki = new LokiClient(client, properties)) {
            logger.info("Checking Pomerium logs...");
            errors.addAll(found(loki, "the Pomerium gate's request that started the login", gateQuery(marker), start));
            if (Boolean.parseBoolean(properties.getCloudValue(cloud, KEYCLOAK_EXTERNAL, "false").trim())) {
                logger.warn("Not checked that KeyCloak logged the failed login: it is another stack's, as {}_KEYCLOAK_EXTERNAL=true, so its logs are not in this cluster's Loki",
                            cloud.name());
            } else {
                logger.info("Checking KeyCloak logs...");
                errors.addAll(found(loki, "KeyCloak's " + LOGIN_ERROR + " event", keyCloakQuery(username), start));
            }
        }
        return errors;
    }

    /**
     * Logs in as a browser would: asks the gate to sign in, which redirects to KeyCloak's login page, then submits that
     * page's form with the unknown username and a wrong password.
     */
    /** The text of an error page, eg KeyCloak's "Invalid parameter: redirect_uri", without its markup, cut short. */
    static String textOf(String html) {
        String text = html.replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private LoginResult attemptLogin(String host, String marker, String username) throws IOException {
        String start = format("https://%s/%s", host, marker);
        logger.info("Starting a login at {}", start);
        BasicCookieStore cookies = new BasicCookieStore();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookies).build()) {
            HttpClientContext context = HttpClientContext.create();
            HttpGet           get     = new HttpGet(start);
            String page = http.execute(get, context, response -> {
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                if (response.getCode() != 200) {
                    throw new IOException(format("Expected KeyCloak's login page from %s but got HTTP %d: %s", start, response.getCode(), textOf(body)));
                }
                return body;
            });
            URI landed = landedOn(context, URI.create(start));
            String action = loginAction(page).orElseThrow(() -> new IOException(format(
                "The page that %s led to, %s, has no KeyCloak login form, one whose action has %s", start, landed, LOGIN_ACTION)));

            HttpPost post = new HttpPost(landed.resolve(action));
            // a redirect is followed no further, as it is what tells whether KeyCloak accepted the login
            post.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("username", username),
                new BasicNameValuePair("password", "wrong-" + marker),
                new BasicNameValuePair("credentialId", "")), StandardCharsets.UTF_8));
            logger.info("Submitting KeyCloak's login form as {} with a wrong password", username);
            return http.execute(post, response -> {
                Header location = response.getFirstHeader("Location");
                String html   = EntityUtils.toString(response.getEntity());
                String expectedMsg = "Invalid username or password.";
                if (!html.contains(expectedMsg)) { // this is a bit fragile as it depends on exact wording. So, just log it.
                    logger.warn("Was expecting to see '{}' in the HTML but got:\n{}", expectedMsg, html);
                }
                return new LoginResult(response.getCode(), location == null ? null : location.getValue());
            });
        }
    }

    private static URI landedOn(HttpClientContext context, URI start) {
        if (context.getRedirectLocations() == null) {
            return start;
        }
        List<URI> all = context.getRedirectLocations().getAll();
        return all.isEmpty() ? start : all.get(all.size() - 1);
    }

    /**
     * @return the action of KeyCloak's login form in the page, unescaped, eg
     * {@code https://id.example.org/realms/polaris/login-actions/authenticate?session_code=a&execution=b}
     */
    static Optional<String> loginAction(String html) {
        Matcher forms = FORM.matcher(html);
        while (forms.find()) {
            Matcher action = ACTION.matcher(forms.group());
            if (action.find() && action.group(1).contains(LOGIN_ACTION)) {
                return Optional.of(action.group(1).replace("&amp;", "&"));
            }
        }
        return Optional.empty();
    }

    /** The lines of the gate's pods that have the marker, which only the request that started the login carries. */
    static String gateQuery(String marker) {
        return format("{namespace=\"%s\", pod=~\"%s-.*\"} |= \"%s\"", SmokeConfig.KUBERNETES_NAMESPACE, CdkHelper.PROXY_SERVICE_NAME, marker);
    }

    /** The lines of KeyCloak's pods of the refused login of the username, which is this run's alone. */
    static String keyCloakQuery(String username) {
        return format("{namespace=\"%s\", pod=~\"%s.*\"} |= \"%s\" |= \"%s\"",
                      SmokeConfig.KUBERNETES_NAMESPACE, KEYCLOAK_POD, LOGIN_ERROR, username.toLowerCase(Locale.ROOT));
    }

    /**
     * @return no error if Loki has a line of the query, retrying while Promtail may not have shipped it yet, else one
     */
    private static List<String> found(LokiClient loki, String what, String query, OffsetDateTime start) {
        try {
            List<String> lines = withRetries(RETRIES, () -> {
                List<String> matched = loki.queryRange(query, start, 10);
                if (matched.isEmpty()) {
                    throw new IllegalStateException(format("Loki has no logs of %s yet", what));
                }
                return matched;
            });
            logger.info("Loki has {}: {}", what, lines.get(0));
            return List.of();
        } catch (Exception e) {
            return List.of(format("Loki has no logs of %s, querying %s since %s: %s", what, query, start, e.getMessage()));
        }
    }
}
