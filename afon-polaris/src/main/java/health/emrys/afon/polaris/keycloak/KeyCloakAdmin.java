package health.emrys.afon.polaris.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.keycloak.KeyCloakHelper.*;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provisions, via the KeyCloak admin REST API, the realm, client and user/password that Polaris
 * trusts. After this has run, a client can obtain a token from KeyCloak using
 *
 * <p>Uses no extra dependencies beyond the Apache HttpClient5 + Jackson already on the classpath.
 */
public class KeyCloakAdmin {

    private static final Logger logger = getLogger(KeyCloakAdmin.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String adminUser;
    private final String adminPassword;

    public KeyCloakAdmin(Properties properties) {
        this(properties, KC_HOSTNAME.getValueFrom(properties));
    }

    public KeyCloakAdmin(Properties properties, String kcHostname) {
        this.baseUrl       = format("http://%s:%d", kcHostname, KEYCLOAK_PORT);
        this.adminUser     = KC_BOOTSTRAP_ADMIN_USERNAME.getValueFrom(properties);
        this.adminPassword = KC_BOOTSTRAP_ADMIN_PASSWORD.getValueFrom(properties);
    }

    /**
     * Ensures the Polaris realm, client and user all exist. Idempotent: anything that already exists
     * is left untouched.
     */
    public void registerPolarisUser(String user, String userPassword) {
        try {
            String adminToken = adminToken();
            createRealm(adminToken, KEYCLOAK_REALM);
            createConfidentialClient(adminToken, KEYCLOAK_REALM, user, userPassword);
            createUser(adminToken, KEYCLOAK_REALM, user, userPassword);
            logger.info(
                "KeyCloak realm '{}' provisioned with client/user '{}'",
                KEYCLOAK_REALM, user);
        } catch (IOException e) {
            throw new RuntimeException("Failed to provision KeyCloak for Polaris", e);
        }
    }

    /**
     * Authenticates against the master realm as the bootstrap admin and returns an access token for
     * the admin REST API.
     */
    public String adminToken() throws IOException {
        String uri = format("%s/realms/master/protocol/openid-connect/token", baseUrl);
        String body = form(Map.of(
            "grant_type", "password",
            "client_id", "admin-cli",
            "username", adminUser,
            "password", adminPassword
                                 ));
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        post.setEntity(new StringEntity(body));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            logger.info("Calling {}", uri);
            return client.execute(
                post, response -> {
                    logger.info("Calling {} resulted in HTTP code {}", uri, response.getCode());
                    if (response.getCode() != HttpStatus.SC_OK) {
                        throw new IOException("Could not obtain KeyCloak admin token: HTTP " + response.getCode());
                    }
                    Map<?, ?> json = MAPPER.readValue(response.getEntity().getContent(), Map.class);
                    return (String) json.get("access_token");
                });
        }
    }

    public void createRealm(String adminToken, String realm) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
            "realm", realm,
            "enabled", true
                                                      ));
        postJson(adminToken, format("%s/admin/realms", baseUrl), json, "realm " + realm);
    }

    /**
     * Creates a confidential client with direct access grants enabled so that the user's
     * username/password can be exchanged for a token.
     */
    public void createConfidentialClient(String adminToken,
                                         String realm,
                                         String clientId,
                                         String clientSecret) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
            "clientId", clientId,
            "secret", clientSecret,
            "enabled", true,
            "protocol", "openid-connect",
            "publicClient", false,
            "serviceAccountsEnabled", true,
            "directAccessGrantsEnabled", true,
            "standardFlowEnabled", false
                                                      ));
        postJson(adminToken, format("%s/admin/realms/%s/clients", baseUrl, realm), json, "client " + clientId);
    }

    public void createUser(String adminToken,
                           String realm,
                           String username,
                           String password) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
            "username", username,
            "enabled", true,
            "emailVerified", true,
            "credentials", List.of(Map.of(
                "type", "password",
                "value", password,
                "temporary", false
                                         ))
                                                      ));
        postJson(adminToken, format("%s/admin/realms/%s/users", baseUrl, realm), json, "user " + username);
    }

    private void postJson(String adminToken, String uri, String json, String what) throws IOException {
        logger.info("Calling {} with: \n{}", uri, json);
        HttpPost post = new HttpPost(uri);
        post.setHeader("Authorization", "Bearer " + adminToken);
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            client.execute(
                post, response -> {
                    int code = response.getCode();
                    logger.info("Calling {} resulted in HTTP code {}", uri, code);
                    if (code == HttpStatus.SC_CREATED) {
                        logger.info("Created KeyCloak {}", what);
                    } else if (code == HttpStatus.SC_CONFLICT) {
                        logger.info("KeyCloak {} already exists - leaving it untouched", what);
                    } else {
                        throw new IOException(format("Could not create KeyCloak %s: HTTP %d", what, code));
                    }
                    return null;
                });
        }
    }

    /**
     * Convenience check used by callers to wait until KeyCloak is serving requests.
     */
    public boolean isReady() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(format("%s/realms/master", baseUrl));
            return client.execute(get, response -> response.getCode() == HttpStatus.SC_OK);
        } catch (IOException e) {
            return false;
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /**
     * Allows the user/password to be provisioned independently of the containers, e.g. against an
     * already-running KeyCloak.
     */
    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        new KeyCloakAdmin(properties)
            .registerPolarisUser(
                AfonProperties.USER_POLARIS_ID.getValueFrom(properties),
                AfonProperties.USER_POLARIS_SECRET.getValueFrom(properties));
    }
}