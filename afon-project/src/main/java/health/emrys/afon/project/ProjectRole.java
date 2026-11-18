package health.emrys.afon.project;

import java.util.Arrays;
import java.util.Optional;

/**
 * The role that a project plays, as {@link health.emrys.afon.config.StackRole} is the role that a stack plays: what the project's file
 * defines, and which stack it deploys, depend on it, not on the id of the project.
 * <ul>
 *     <li>{@link #IDENTITY}: the central identity, ie the stack with the identity role, eg Keycloak, on its own, which every TRE project
 *         uses to sign in.  It has no Data Providers, clients or members, and lives until it is closed, not a day.  It is the project that holds
 *         the egress IP that the identity leaves from, its own, so that what it depends on, eg its database, allowlists one address that does
 *         not change when it is rebuilt;</li>
 *     <li>{@link #TRE}: a trusted research environment, with its identity registered in the central identity, its Data Providers, and its Git;</li>
 *     <li>{@link #DATA}: a Data Provider, ie the Polaris that holds the catalogs that the TREs read.  It has no clients, group or members in the
 *         central identity, and, as the data it holds, lives until it is taken down on purpose, not a day.  The Data Provider is another organisation in
 *         practice, which runs it with its own credentials, so the file is the Data Provider's.</li>
 * </ul>
 * One identity project coexists with any number of TRE projects, all made from the same env if the deployer wants, as each has its own config
 * and state.
 */
public enum ProjectRole {

    IDENTITY("identity"),
    TRE("tre"),
    DATA("data");

    private final String value;

    ProjectRole(String value) {
        this.value = value;
    }

    /**
     * @return the role as it is in the project file, in lower case
     */
    public String value() {
        return value;
    }

    /**
     * @return the role with the value, or empty if there is none
     */
    public static Optional<ProjectRole> of(String value) {
        return Arrays.stream(values()).filter(role -> role.value.equals(value)).findFirst();
    }

    @Override
    public String toString() {
        return value;
    }
}
