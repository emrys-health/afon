package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.*;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static java.lang.String.format;

public class KeyCloakSmokeMain {

    private static final Logger           logger = LoggerFactory.getLogger(KeyCloakSmokeMain.class);

    private final        KeyCloakProvider keyCloak;
    private final AfonUserProvider users;

    public static void main(String[] args) throws Exception {
        KeyCloakSmokeMain app    = new KeyCloakSmokeMain();
        List<String>      errors = app.checkKeyCloakAccess();
        if (errors.size() > 0) {
            String msg = String.join("\n", errors);
            logger.error("Errors:\n{}", msg);
            throw new RuntimeException(msg);
        } else {
            logger.info("KeyCloak tests passed");
        }
    }

    private final AfonPropertyProvider properties;

    public KeyCloakSmokeMain() {
        SmokeConfig config = new SmokeConfig();
        AfonCloud   cloud  = config.cloud();
        this.properties = config.getProperties();
        this.keyCloak   = new KeyCloakProvider(properties.getOidcDomainName(cloud));
        this.users      = new FileUserProvider();
    }

    private List<String> checkKeyCloakAccess() {
        List<String> errors = new ArrayList<>();
        for (AfonUser user : users.getUsers()) {
            errors.addAll(check(user));
        }
        return errors;
    }

    List<String> check(AfonUser user) {
        List<String> errors = new ArrayList<>();
        String       token;
        try {
            token = keyCloak.getTokenFor(KEYCLOAK_REALM, user.username(), user.password());
            errors.add(format("Could get a token for  %s", user.username()));
        } catch (IllegalStateException e) {
           logger.info(format("Could not get token for %s. Error = {}", user.username()), e.getMessage());
        } catch (IOException e) {
            errors.add(format("IO error getting token for %s. Error = %s", user.username(), e.getMessage()));
        }
        return errors;
    }

    private boolean isAdmin(AfonUser user) {
        return user.username().equals(properties.getValue(AfonProperty.USER_ID));
    }
}
