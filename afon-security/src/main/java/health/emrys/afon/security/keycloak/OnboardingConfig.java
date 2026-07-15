package health.emrys.afon.security.keycloak;

import java.util.List;

public interface OnboardingConfig {

    /**
     * A Guacamole user. {@code email} is the OIDC identity (the JupyterHub username and the
     * Guacamole login); {@code slug} is a DNS/label-safe form used for the per-user VNC Service
     * name and the pod label, since an email address is valid as neither.
     */
    record EmrysUser(String userName, String email, String slug, String password) {}

    List<EmrysUser> getUsers();

}
