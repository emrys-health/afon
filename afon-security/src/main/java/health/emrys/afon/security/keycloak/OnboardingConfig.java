package health.emrys.afon.security.keycloak;

import java.util.List;

public interface OnboardingConfig {

    static EmrysUser createUser(String userName, String password, String email) {
        return new EmrysUser(userName,
                             email,
                             null,
                             password,
                             "undefined first name",
                             "undefined last name");
    }

    static EmrysUser createUser(String userName, String password) {
        return createUser(userName, password, null);
    }

    /**
     * A Guacamole user. {@code email} is the OIDC identity (the JupyterHub username and the
     * Guacamole login); {@code slug} is a DNS/label-safe form used for the per-user VNC Service
     * name and the pod label, since an email address is valid as neither.
     */
    record EmrysUser(String userName,   // for login via KeyCloak
                     String email,      // used to identify users across systems vai X-Forwarded-Email header stamped by OAuth2-Proxy
                     String slug,       // must be RFC-1123 DNS  compliant, therefore cannot use email for the slug
                     String password,   // for login via KeyCloak
                     String firstName,  // need first and last name for user registration to be complete in KeyCloak
                     String lastName
    ) {}

    List<EmrysUser> getUsers();

}
