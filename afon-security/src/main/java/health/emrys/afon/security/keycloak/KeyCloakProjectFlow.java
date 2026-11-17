package health.emrys.afon.security.keycloak;

import java.util.List;
import java.util.Map;

/**
 * The browser login of a project's client, as a tree of KeyCloak authentication flows and executions, which
 * {@link KeyCloakProvider} makes in the realm and binds to the client: only the project's members get a token for it,
 * and only after a second factor, which sets its {@code acr}.
 * <pre>
 * &lt;client&gt;-browser
 *   &lt;client&gt;-authenticate         REQUIRED     one of the two, so that a session that has the second factor already is let through
 *     Cookie                      ALTERNATIVE  the single sign-on session, at the level that the client asks for, see below
 *     &lt;client&gt;-forms              ALTERNATIVE
 *       &lt;client&gt;-level-1          CONDITIONAL  the first factor, for a session below level 1
 *         Condition - level 1     REQUIRED
 *         Username Password Form  REQUIRED
 *       &lt;client&gt;-level-2          CONDITIONAL  the second factor, for a session below level 2
 *         Condition - level 2     REQUIRED
 *         OTP Form                REQUIRED     a user with no authenticator is walked through enrolling one
 *   &lt;client&gt;-members              CONDITIONAL  after the user is known, however they were: by the cookie too
 *     Condition - not a member    REQUIRED     the user lacks the client's role of the project
 *     Deny access                 REQUIRED
 * </pre>
 * The client's default ACR value is the project's {@code acr}, which the realm maps to level {@value #MFA_LEVEL}, so a login
 * to the client is at level 2 unless it asks for less, and its token says so: {@code acr} is the project's, eg {@code mfa}.  A
 * token of a lower level says {@code 1}, which no consumer admits.  The membership check is outside the choice between the
 * cookie and the forms, so that a session made for another project is no way in: it leads only to the denial.
 */
final class KeyCloakProjectFlow {

    /** The level of authentication that a second factor gives, which the realm maps the project's {@code acr} to. */
    static final String MFA_LEVEL = "2";

    /** The seconds that a level of authentication holds in a session, as the realm's SSO session max does. */
    static final String LEVEL_MAX_AGE = "36000";

    static final String COOKIE = "auth-cookie";
    static final String PASSWORD_FORM = "auth-username-password-form";
    static final String OTP_FORM = "auth-otp-form";
    static final String LEVEL_CONDITION = "conditional-level-of-authentication";
    static final String ROLE_CONDITION = "conditional-user-role";
    static final String DENY = "deny-access-authenticator";

    static final String REQUIRED = "REQUIRED";
    static final String ALTERNATIVE = "ALTERNATIVE";
    static final String CONDITIONAL = "CONDITIONAL";

    private KeyCloakProjectFlow() {
    }

    /**
     * A flow, if it has no provider, or an execution of the provider, in the flow that holds it.
     *
     * @param name        the alias of a flow, or the alias of the configuration of an execution that has one
     * @param provider    the provider of an execution, or {@code null} for a flow
     * @param requirement how the flow or execution takes part in the one that holds it
     * @param config      the configuration of an execution, if any
     * @param children    what a flow holds, in order
     */
    record Node(String name, String provider, String requirement, Map<String, String> config, List<Node> children) {

        boolean isFlow() {
            return provider == null;
        }
    }

    /** @return the alias of the top flow of the client's login */
    static String flowAlias(String clientId) {
        return clientId + "-browser";
    }

    /**
     * @param clientId  the client, eg {@code xyz-yyyy-nnnn-tre}
     * @param projectId the project, which names the client role that marks a member
     * @return the top flow of the login of the client
     */
    static Node of(String clientId, String projectId) {
        return flow(flowAlias(clientId), REQUIRED,
            flow(clientId + "-authenticate", REQUIRED,
                execution(COOKIE, ALTERNATIVE, null, null),
                flow(clientId + "-forms", ALTERNATIVE,
                    flow(clientId + "-level-1", CONDITIONAL,
                        execution(LEVEL_CONDITION, REQUIRED, clientId + "-level-1", Map.of("loa-condition-level", "1", "loa-max-age", LEVEL_MAX_AGE)),
                        execution(PASSWORD_FORM, REQUIRED, null, null)),
                    flow(clientId + "-level-2", CONDITIONAL,
                        execution(LEVEL_CONDITION, REQUIRED, clientId + "-level-2", Map.of("loa-condition-level", MFA_LEVEL, "loa-max-age", LEVEL_MAX_AGE)),
                        execution(OTP_FORM, REQUIRED, null, null)))),
            flow(clientId + "-members", CONDITIONAL,
                execution(ROLE_CONDITION, REQUIRED, clientId + "-not-a-member", Map.of("condUserRole", clientId + "." + projectId, "negate", "true")),
                execution(DENY, REQUIRED, clientId + "-deny", Map.of("denyErrorMessage", "You are not a member of this project"))));
    }

    private static Node flow(String alias, String requirement, Node... children) {
        return new Node(alias, null, requirement, Map.of(), List.of(children));
    }

    private static Node execution(String provider, String requirement, String configAlias, Map<String, String> config) {
        return new Node(configAlias, provider, requirement, config == null ? Map.of() : config, List.of());
    }
}
