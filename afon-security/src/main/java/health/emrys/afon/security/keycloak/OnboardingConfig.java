package health.emrys.afon.security.keycloak;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
     * An RFC-1123 DNS label: lower-case alphanumerics and hyphens, starting and ending with an
     * alphanumeric, at most 63 characters. Kubernetes applies this to Service names, so a slug that
     * violates it fails the apply rather than the build.
     */
    Pattern RFC_1123_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    int RFC_1123_MAX_LENGTH = 63;

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
    ) {
        public EmrysUser {
            // KeyCloak lower-cases e-mail addresses, so the identity that arrives at runtime (as the
            // JupyterHub username and oauth2-proxy's X-Forwarded-Email header) is always lower case.
            // Several consumers use this value as a case-sensitive lookup key — the Guacamole
            // provisioning SQL's guacamole_entity rows and the hub's _GUAC_SLUGS dict among them — so
            // a roster entry with any upper case silently produces a user who authenticates fine but
            // owns no Guacamole connection and whose pod never gets its guacamole-user label.
            // Normalising here keeps every consumer agreeing on one spelling.
            if (email != null) {
                email = email.toLowerCase(Locale.ROOT);
            }
            // The slug becomes a Service name and a pod label value; Kubernetes rejects both if it is
            // not an RFC-1123 label. Fail at construction, where the offending roster entry is obvious,
            // rather than midway through a tofu apply.
            if (slug != null && !isRfc1123Label(slug)) {
                throw new IllegalArgumentException(
                    "slug '" + slug + "' for user '" + userName + "' is not RFC-1123 DNS compliant: "
                    + "expected up to " + RFC_1123_MAX_LENGTH + " characters of lower-case "
                    + "alphanumerics or '-', starting and ending with an alphanumeric");
            }
        }
    }

    /**
     * Note that callers deriving a longer name from a slug (the per-user VNC Service is
     * {@code guac-vnc-<slug>}) need to leave headroom: it is the derived name Kubernetes validates.
     */
    static boolean isRfc1123Label(String candidate) {
        return candidate.length() <= RFC_1123_MAX_LENGTH
               && RFC_1123_LABEL.matcher(candidate).matches();
    }

    List<EmrysUser> getUsers();

}
