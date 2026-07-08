package health.emrys.afon.cdk;

import java.util.List;

public class Onboarding {
    /**
     * A Guacamole user. {@code email} is the OIDC identity (the JupyterHub username and the
     * Guacamole login); {@code slug} is a DNS/label-safe form used for the per-user VNC Service
     * name and the pod label, since an email address is valid as neither.
     */
    public record GuacUser(String email, String slug) {}

    /**
     * Fixed roster. Guacamole's file-based auth and the per-user VNC Services must enumerate users
     * up front, so adding a user means editing this list and redeploying.
     * TODO - read from a CSV file
     */
    private final List<GuacUser> users = List.of(
        new GuacUser("phil.henry@emrys.health", "phil-henry"),
        new GuacUser("will@emrys.health", "will"));


    public List<GuacUser> getUsers() {
        return users;
    }

}
