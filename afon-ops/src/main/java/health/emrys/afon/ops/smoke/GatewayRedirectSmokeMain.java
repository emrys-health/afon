package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloudProperty.JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;

/**
 * Checks that Pomerium's authentication redirect chain is wired end-to-end:
 * <ol>
 *   <li>An unauthenticated request to {@code https://<jupyter>/} redirects (3xx) to
 *       {@code https://auth.<jupyter>/...} (verifying the Jupyter ingress, TLS cert and Pomerium's
 *       {@code authenticate_service_url}).</li>
 *   <li>Following that redirect to {@code https://auth.<jupyter>/...} redirects (3xx) to the OIDC
 *       issuer's authorization endpoint with {@code redirect_uri=https://auth.<jupyter>/oauth2/callback}
 *       (verifying the {@code auth.<jupyter>} DNS record, TLS cert, ingress and Pomerium's OIDC config).</li>
 * </ol>
 */
public class GatewayRedirectSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(GatewayRedirectSmokeMain.class);

    private final AfonCloud            cloud;
    private final AfonPropertyProvider properties;

    public GatewayRedirectSmokeMain(AfonCloud cloud, AfonPropertyProvider properties) {
        this.cloud      = cloud;
        this.properties = properties;
    }

    public static void main(String[] args) throws Exception {
        AfonPropertyProvider properties = smokeTestProperties();
        GatewayRedirectSmokeMain app = new GatewayRedirectSmokeMain(new SmokeConfig(properties).cloud(), properties);
        List<String> errors = app.check();
        if (!errors.isEmpty()) {
            String msg = String.join("\n", errors);
            logger.error(msg);
            throw new RuntimeException(msg);
        }
        logger.info("Test passed");
    }

    record RedirectResponse(int status, String location) {}

    public List<String> check() throws Exception {
        String host = properties.getCloudValue(cloud, JUPYTER_DOMAIN_NAME);
        if (host == null || host.isBlank()) {
            return List.of(format("%s_JUPYTER_DOMAIN_NAME is not set, so there is no gateway to check", cloud.name()));
        }
        host = host.trim();
        String issuer = properties.getOidcIssuerUrl(cloud).replaceAll("/+$", "");

        List<String> errors = new ArrayList<>();
        try (CloseableHttpClient http = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
            .build()) {
            String jupyterUrl = "https://" + host + "/";
            logger.info("Requesting {}", jupyterUrl);
            RedirectResponse first = getNoRedirect(http, jupyterUrl);
            errors.addAll(validateAuthRedirect(host, first.status(), first.location()));
            if (!errors.isEmpty()) {
                return errors;
            }

            logger.info("Following redirect to {}", first.location());
            RedirectResponse second = getNoRedirect(http, first.location());
            errors.addAll(validateIdpRedirect(host, issuer, second.status(), second.location()));
        }
        return errors;
    }

    private static RedirectResponse getNoRedirect(CloseableHttpClient http, String url) throws IOException {
        return http.execute(new HttpGet(url), response -> {
            EntityUtils.consumeQuietly(response.getEntity());
            Header location = response.getFirstHeader("Location");
            return new RedirectResponse(response.getCode(), location == null ? null : location.getValue());
        });
    }

    static List<String> validateAuthRedirect(String jupyterHost, int status, String location) {
        String expectedPrefix = "https://auth." + jupyterHost + "/";
        if (status < 300 || status >= 400) {
            return List.of(format("Expected 3xx redirect from https://%s/ to %s, got HTTP %d (Location: %s)",
                                  jupyterHost, expectedPrefix, status, location));
        }
        if (location == null || !location.startsWith(expectedPrefix)) {
            return List.of(format("Expected redirect from https://%s/ to start with %s, got %s",
                                  jupyterHost, expectedPrefix, location));
        }
        return List.of();
    }

    static List<String> validateIdpRedirect(String jupyterHost, String issuer, int status, String location) {
        String expectedIssuerPrefix = issuer.replaceAll("/+$", "") + "/";
        String expectedCallback     = "https://auth." + jupyterHost + "/oauth2/callback";
        if (status < 300 || status >= 400) {
            return List.of(format("Expected 3xx redirect from https://auth.%s/ to %s, got HTTP %d (Location: %s)",
                                  jupyterHost, expectedIssuerPrefix, status, location));
        }
        if (location == null || !location.startsWith(expectedIssuerPrefix)) {
            return List.of(format("Expected redirect from https://auth.%s/ to start with %s, got %s",
                                  jupyterHost, expectedIssuerPrefix, location));
        }
        String redirectUri = queryParam(URI.create(location).getRawQuery(), "redirect_uri");
        if (!expectedCallback.equals(redirectUri)) {
            return List.of(format("Expected redirect_uri=%s in %s, got %s",
                                  expectedCallback, location, redirectUri));
        }
        return List.of();
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
