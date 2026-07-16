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
    record EmrysUser(String userName,
                     String email,
                     String slug,
                     String password,
                     String firstName,
                     String lastName) {}

    List<EmrysUser> getUsers();

}
