package health.emrys.afon.security.keycloak;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The changes that turn a copy of KeyCloak's built-in {@code browser} authentication flow into one
 * that <em>always</em> demands a TOTP second factor.
 *
 * <p>KeyCloak ships the browser flow with its OTP step inside a CONDITIONAL sub-flow ("Browser -
 * Conditional 2FA") whose conditions let anyone who has not voluntarily enrolled an authenticator app
 * straight through - which is everyone. Forcing the second factor means, on that sub-flow alone:
 *
 * <ul>
 *   <li>disabling every {@code conditional-*} execution in it - the guards that decide whether a
 *       second factor is asked for at all, and which of OTP/WebAuthn/recovery codes to ask for;</li>
 *   <li>promoting the sub-flow itself from CONDITIONAL to REQUIRED, since a CONDITIONAL sub-flow with
 *       no live condition left in it is simply skipped;</li>
 *   <li>making the {@value #OTP_FORM} execution REQUIRED, which is what makes KeyCloak walk a user
 *       with no authenticator app through enrolment mid-login rather than waving them past.</li>
 * </ul>
 *
 * <p>Nothing outside that sub-flow is touched. That matters: KeyCloak 26 puts a second
 * {@code conditional-user-configured} in the unrelated Organization sub-flow, and the SSO cookie step
 * stays ALTERNATIVE so a returning user with a live session is not re-challenged.
 *
 * <p>The logic lives apart from {@link KeyCloakProvider}'s HTTP so it can be tested against the
 * execution list the admin API returns.
 */
public final class KeyCloakMfa {

    /** The OTP form users type their six digits into during a browser login. */
    public static final String OTP_FORM           = "auth-otp-form";
    /** The direct-grant flow's OTP step, which expects the six digits as a request parameter. */
    public static final String DIRECT_GRANT_OTP   = "direct-grant-validate-otp";
    /** KeyCloak names every condition provider {@code conditional-*}; none may survive in the 2FA sub-flow. */
    public static final String CONDITION_PREFIX   = "conditional-";

    static final String REQUIRED = "REQUIRED";
    static final String DISABLED = "DISABLED";

    private static final String PROVIDER_ID   = "providerId";
    private static final String REQUIREMENT   = "requirement";
    private static final String IS_SUB_FLOW   = "authenticationFlow";
    private static final String NESTING_LEVEL = "level";

    private KeyCloakMfa() {
    }

    /**
     * Rewrites, in place, the executions of a browser flow so that OTP is mandatory for every user.
     *
     * @param executions the flow's executions in the order the admin API lists them (depth-first,
     *                   each carrying its nesting {@code level}).
     * @return only those executions whose requirement actually changed, ordered so that they can be
     *         PUT back one at a time: conditions are disabled before the sub-flow around them becomes
     *         REQUIRED, so the flow is never momentarily left with a live condition in a
     *         non-conditional sub-flow. An already-converged flow yields an empty list, as does one
     *         with no OTP step to force.
     */
    public static List<Map<String, Object>> requireOtp(List<Map<String, Object>> executions) {
        int otp = indexOf(executions, OTP_FORM);
        if (otp < 0) {
            return List.of();
        }
        int subFlow = enclosingSubFlow(executions, otp);
        if (subFlow < 0) {
            // An OTP form already at the top level of the flow needs no sub-flow promoting.
            List<Map<String, Object>> changed = new ArrayList<>();
            setRequirement(executions.get(otp), REQUIRED, changed);
            return changed;
        }
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (Map<String, Object> child : childrenOf(executions, subFlow)) {
            if (String.valueOf(child.get(PROVIDER_ID)).startsWith(CONDITION_PREFIX)) {
                setRequirement(child, DISABLED, conditions);
            }
        }
        List<Map<String, Object>> changed = new ArrayList<>(conditions);
        setRequirement(executions.get(subFlow), REQUIRED, changed);
        setRequirement(executions.get(otp), REQUIRED, changed);
        return changed;
    }

    /**
     * Rewrites, in place, the executions of a direct-grant flow so that OTP is never asked for.
     *
     * <p>The counterpart to {@link #requireOtp}, and required by it rather than an alternative to it.
     * KeyCloak's built-in direct-grant flow demands OTP from any user who <em>has</em> an OTP
     * credential - so the moment browser MFA makes a human enrol, that same human's headless logins
     * (Spark, Trino, the CLI) start failing with "Invalid user credentials", since a batch job has no
     * one to read an authenticator app. Taking the OTP step out of the direct-grant flow keeps those
     * working. The second factor is thus a property of interactive logins only; a direct grant is
     * protected by the confidential client's secret instead.
     *
     * @return the single execution whose requirement changed, or empty if the flow already has no OTP
     *         step in play.
     */
    public static List<Map<String, Object>> skipOtp(List<Map<String, Object>> executions) {
        int otp = indexOf(executions, DIRECT_GRANT_OTP);
        if (otp < 0) {
            return List.of();
        }
        // Disabling the sub-flow that holds the OTP step takes it and its conditions out of the flow
        // in one call; an OTP step at the top level has to be disabled on its own.
        int                       subFlow = enclosingSubFlow(executions, otp);
        List<Map<String, Object>> changed = new ArrayList<>();
        setRequirement(executions.get(subFlow < 0 ? otp : subFlow), DISABLED, changed);
        return changed;
    }

    private static int indexOf(List<Map<String, Object>> executions, String providerId) {
        for (int i = 0; i < executions.size(); i++) {
            if (providerId.equals(executions.get(i).get(PROVIDER_ID))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the sub-flow the execution at {@code index} sits in: scanning back, the nearest
     * sub-flow one nesting level shallower. -1 if the execution is at the top level of the flow.
     */
    private static int enclosingSubFlow(List<Map<String, Object>> executions, int index) {
        int parentLevel = levelOf(executions.get(index)) - 1;
        if (parentLevel < 0) {
            return -1;
        }
        for (int i = index - 1; i >= 0; i--) {
            Map<String, Object> candidate = executions.get(i);
            if (levelOf(candidate) == parentLevel && isSubFlow(candidate)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Everything nested under the sub-flow at {@code index}: the executions that follow it until the
     * listing comes back out to the sub-flow's own level.
     */
    private static List<Map<String, Object>> childrenOf(List<Map<String, Object>> executions, int index) {
        int                       level    = levelOf(executions.get(index));
        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = index + 1; i < executions.size() && levelOf(executions.get(i)) > level; i++) {
            children.add(executions.get(i));
        }
        return children;
    }

    private static boolean isSubFlow(Map<String, Object> execution) {
        return Boolean.TRUE.equals(execution.get(IS_SUB_FLOW));
    }

    private static int levelOf(Map<String, Object> execution) {
        Object level = execution.get(NESTING_LEVEL);
        return level instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Sets the requirement, recording the execution as needing a PUT only if that actually changed it
     * (so re-running against an already-MFA flow makes no admin API calls at all).
     */
    private static void setRequirement(Map<String, Object> execution,
                                       String requirement,
                                       List<Map<String, Object>> changed) {
        if (requirement.equals(execution.get(REQUIREMENT))) {
            return;
        }
        execution.put(REQUIREMENT, requirement);
        changed.add(execution);
    }
}
