package health.emrys.afon.config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An Afon user.
 * {@code email} is the OIDC identity (the JupyterHub username and the
 * Guacamole login)
 * {@code slug} is RFC-1123 compliant form used for the per-user VNC Service
 * name and the pod label, since an email address is valid as neither.
 * <p>
 * A user has one or more {@link Role}s: it is an admin, a member, or both.  The users of {@code users.csv} are admins, who bootstrap and operate
 * the TRE, and are also its members unless it is a project's, where the members are those of {@code project.yaml}, who have no password
 * here: their identity provider invites them.
 */
public record AfonUser(String username,   // for login via KeyCloak
                       String password,   // for login via KeyCloak
                       String email,      // for user auth across systems via X-Forwarded-Email header stamped by OAuth2-Proxy
                       String slug,       // for user registration in KeyCloak, must be RFC-1123 DNS compliant, therefore cannot use email for the slug
                       String firstName,
                       String lastName,
                       Set<Role> roles){  // at least one

    public static final String UNDEFINED = "undefined";

    /**
     * What a user is to the TRE.
     */
    public enum Role {
        /** Operates the TRE: has its Keycloak login and password, its Polaris principals and its Git account. */
        ADMIN,
        /** Uses the TRE: is served by JupyterHub and its remote desktop. */
        MEMBER
    }

    /**
     * An RFC-1123 pattern: lower-case alphanumerics and hyphens, starting and ending with alphanumeric, at most 63 characters.
     * Kubernetes applies this to Service names, so a slug that violates it, fails apply rather than the build.
     */
    private static final Pattern RFC_1123_PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?"); // needed for user registration in KeyCloak
    private static final int RFC_1123_MAX_LENGTH = 63;

    /**
     * @return an admin who is also a member, as every user of {@code users.csv} is outside a project
     */
    public AfonUser(String username, String password, String email, String slug, String firstName, String lastName) {
        this(username, password, email, slug, firstName, lastName, EnumSet.allOf(Role.class));
    }

    /**
     * @return whether the user has the role
     */
    public boolean has(Role role) {
        return roles.contains(role);
    }

    /**
     * @return this user with only these roles, at least one, eg as an admin who does not use the TRE, as those of {@code users.csv} are in a project
     */
    public AfonUser withRoles(Role... roles) {
        return new AfonUser(username, password, email, slug, firstName, lastName, Set.of(roles));
    }

    public AfonUser {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("user '" + username + "' has no role: it is an admin, a member, or both");
        }
        roles = Collections.unmodifiableSet(EnumSet.copyOf(roles));
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
                    "slug '" + slug + "' for user '" + username + "' is not RFC-1123 DNS compliant: "
                            + "expected up to " + RFC_1123_MAX_LENGTH + " characters of lower-case "
                            + "alphanumerics or '-', starting and ending with an alphanumeric");
        }
    }

    /**
     * Note that callers deriving a longer name from a slug (the per-user VNC Service is
     * {@code guac-vnc-<slug>}) need to leave headroom: it is the derived name Kubernetes validates.
     */
     private boolean isRfc1123Label(String candidate) {
        return candidate.length() <= RFC_1123_MAX_LENGTH
                && RFC_1123_PATTERN.matcher(candidate).matches();
    }

}
