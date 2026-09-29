package health.emrys.afon.ops.steps;

import java.util.Arrays;

/**
 * The actions on a stack: {@code deploy} just deploys it (waiting for DNS to propagate), {@code setup} also
 * configures it (eg the OIDC users and the Polaris catalogs), and {@code delete} deletes it.
 */
public enum StackAction {
    DELETE, DEPLOY, SETUP;

    /**
     * @return the action named, case-insensitively, or {@code null} if none
     */
    public static StackAction of(String name) {
        return Arrays.stream(values())
                     .filter(action -> action.name().equalsIgnoreCase(name))
                     .findFirst()
                     .orElse(null);
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
