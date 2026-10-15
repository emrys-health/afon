package health.emrys.afon.config;

import java.util.Arrays;
import java.util.Optional;

/**
 * The role that a stack plays, which code depends on rather than on the id of the stack, ie the stacks file says which
 * stack of a cloud has which role, with {@code role:}, so the ids and the components of the stacks are free to change:
 * <ul>
 *     <li>{@link #CATALOG}: the catalog provider on its own, eg Polaris;</li>
 *     <li>{@link #IDENTITY}: the identity provider on its own, eg Keycloak;</li>
 *     <li>{@link #TRE}: the trusted research environment, ie the stack of all the components.</li>
 * </ul>
 * A cloud has at most one stack with a role, and a stack has at most one role.  Stacks with none, eg one added in the
 * stacks file, are managed all the same, but no code looks for them by role.
 */
public enum StackRole {

    CATALOG("catalog"),
    IDENTITY("identity"),
    TRE("tre");

    private final String value;

    StackRole(String value) {
        this.value = value;
    }

    /**
     * @return the role as it is in the stacks file, in lower case
     */
    public String value() {
        return value;
    }

    /**
     * @return the role with the value, or empty if there is none
     */
    public static Optional<StackRole> of(String value) {
        return Arrays.stream(values()).filter(role -> role.value.equals(value)).findFirst();
    }

    @Override
    public String toString() {
        return value;
    }
}
