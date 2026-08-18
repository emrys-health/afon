package health.emrys.afon.security.keycloak;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.security.keycloak.KeyCloakMfa.OTP_FORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyCloakMfaTest {

    private static final String CONDITIONAL = "CONDITIONAL";
    private static final String REQUIRED    = "REQUIRED";
    private static final String DISABLED    = "DISABLED";
    private static final String ALTERNATIVE = "ALTERNATIVE";

    private static final String ORG_SUB_FLOW     = "browser-mfa Browser - Conditional Organization";
    private static final String ORG_CONDITION    = "Condition - user configured (organization)";
    private static final String TWO_FA_SUB_FLOW  = "browser-mfa Browser - Conditional 2FA";
    private static final String TWO_FA_CONDITION = "Condition - user configured (2FA)";
    private static final String CREDENTIAL_CONDITION = "Condition - credential";

    /**
     * The executions KeyCloak 26.6 returns for a fresh copy of its built-in browser flow, in the order
     * and shape (nesting level, sub-flow marker) the admin API lists them. Note the two separate
     * "user configured" conditions - one guarding organization login, one guarding the second factor.
     */
    private static List<Map<String, Object>> copiedBrowserFlow() {
        List<Map<String, Object>> executions = new ArrayList<>();
        executions.add(execution("Cookie", "auth-cookie", ALTERNATIVE, 0, false));
        executions.add(execution("Kerberos", "auth-spnego", DISABLED, 0, false));
        executions.add(execution("Identity Provider Redirector", "identity-provider-redirector", ALTERNATIVE, 0, false));
        executions.add(execution("browser-mfa Organization", null, ALTERNATIVE, 0, true));
        executions.add(execution(ORG_SUB_FLOW, null, CONDITIONAL, 1, true));
        executions.add(execution(ORG_CONDITION, "conditional-user-configured", REQUIRED, 2, false));
        executions.add(execution("Organization Identity-First Login", "organization", ALTERNATIVE, 2, false));
        executions.add(execution("browser-mfa forms", null, ALTERNATIVE, 0, true));
        executions.add(execution("Username Password Form", "auth-username-password-form", REQUIRED, 1, false));
        executions.add(execution(TWO_FA_SUB_FLOW, null, CONDITIONAL, 1, true));
        executions.add(execution(TWO_FA_CONDITION, "conditional-user-configured", REQUIRED, 2, false));
        executions.add(execution(CREDENTIAL_CONDITION, "conditional-credential", REQUIRED, 2, false));
        executions.add(execution("OTP Form", OTP_FORM, REQUIRED, 2, false));
        executions.add(execution("WebAuthn Authenticator", "webauthn-authenticator", DISABLED, 2, false));
        executions.add(execution("Recovery Authentication Code Form", "auth-recovery-authn-code-form", DISABLED, 2, false));
        return executions;
    }

    private static Map<String, Object> execution(String displayName,
                                                 String providerId,
                                                 String requirement,
                                                 int level,
                                                 boolean subFlow) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("id", displayName);
        execution.put("displayName", displayName);
        execution.put("providerId", providerId);
        execution.put("requirement", requirement);
        execution.put("level", level);
        if (subFlow) {
            execution.put("authenticationFlow", true);
        }
        return execution;
    }

    private static Map<String, Object> named(List<Map<String, Object>> executions, String displayName) {
        return executions.stream()
                         .filter(x -> displayName.equals(x.get("displayName")))
                         .findFirst()
                         .orElseThrow(() -> new AssertionError("No execution named " + displayName));
    }

    /**
     * OTP must be unconditional: every guard in the 2FA sub-flow is disabled and the sub-flow itself
     * promoted from CONDITIONAL to REQUIRED, so the OTP form always runs.
     */
    @Test
    public void otpIsForcedOnACopyOfTheBuiltInBrowserFlow() {
        List<Map<String, Object>> executions = copiedBrowserFlow();

        KeyCloakMfa.requireOtp(executions);

        assertEquals(DISABLED, named(executions, TWO_FA_CONDITION).get("requirement"));
        assertEquals(DISABLED, named(executions, CREDENTIAL_CONDITION).get("requirement"));
        assertEquals(REQUIRED, named(executions, TWO_FA_SUB_FLOW).get("requirement"));
        assertEquals(REQUIRED, named(executions, "OTP Form").get("requirement"));
    }

    /**
     * Only the 2FA sub-flow is rewritten. KeyCloak 26 puts an identically-named "user configured"
     * condition in the Organization sub-flow, and disabling that one would silently change how
     * organization login behaves.
     */
    @Test
    public void theRestOfTheFlowIsUntouched() {
        List<Map<String, Object>> executions = copiedBrowserFlow();

        KeyCloakMfa.requireOtp(executions);

        assertEquals(REQUIRED, named(executions, ORG_CONDITION).get("requirement"));
        assertEquals(CONDITIONAL, named(executions, ORG_SUB_FLOW).get("requirement"));
        // The SSO cookie stays ALTERNATIVE, so a returning user with a live session is not re-challenged.
        assertEquals(ALTERNATIVE, named(executions, "Cookie").get("requirement"));
        assertEquals(DISABLED, named(executions, "Kerberos").get("requirement"));
        assertEquals(ALTERNATIVE, named(executions, "Identity Provider Redirector").get("requirement"));
        assertEquals(ALTERNATIVE, named(executions, "browser-mfa forms").get("requirement"));
        assertEquals(REQUIRED, named(executions, "Username Password Form").get("requirement"));
    }

    /**
     * Only the executions that actually changed come back, since each one costs an admin API call.
     * Conditions come first, so the flow is never left with a live condition inside a sub-flow that is
     * no longer conditional.
     */
    @Test
    public void onlyChangedExecutionsAreReturnedConditionsFirst() {
        List<Map<String, Object>> changed = KeyCloakMfa.requireOtp(copiedBrowserFlow());

        // The OTP form is REQUIRED in the built-in flow already, so only the two conditions and the
        // sub-flow need writing back.
        assertEquals(
            List.of(TWO_FA_CONDITION, CREDENTIAL_CONDITION, TWO_FA_SUB_FLOW),
            changed.stream().map(x -> x.get("displayName")).toList());
    }

    /** Re-running against an already-converged flow asks for no admin API calls at all. */
    @Test
    public void reapplyingChangesNothing() {
        List<Map<String, Object>> executions = copiedBrowserFlow();
        KeyCloakMfa.requireOtp(executions);

        assertTrue(KeyCloakMfa.requireOtp(executions).isEmpty());
    }

    private static final String DIRECT_GRANT_OTP_SUB_FLOW = "direct-grant-no-otp Direct Grant - Conditional OTP";

    /** The executions KeyCloak returns for a fresh copy of its built-in {@code direct grant} flow. */
    private static List<Map<String, Object>> copiedDirectGrantFlow() {
        List<Map<String, Object>> executions = new ArrayList<>();
        executions.add(execution("Username Validation", "direct-grant-validate-username", REQUIRED, 0, false));
        executions.add(execution("Password", "direct-grant-validate-password", REQUIRED, 0, false));
        executions.add(execution(DIRECT_GRANT_OTP_SUB_FLOW, null, CONDITIONAL, 0, true));
        executions.add(execution("Condition - user configured", "conditional-user-configured", REQUIRED, 1, false));
        executions.add(execution("OTP", KeyCloakMfa.DIRECT_GRANT_OTP, REQUIRED, 1, false));
        return executions;
    }

    /**
     * A headless login must not be asked for a second factor, or every user who enrols an
     * authenticator app for the browser breaks their own Spark and Trino jobs. Disabling the sub-flow
     * takes the OTP step and its condition out in one call.
     */
    @Test
    public void otpIsTakenOutOfTheDirectGrantFlow() {
        List<Map<String, Object>> executions = copiedDirectGrantFlow();

        List<Map<String, Object>> changed = KeyCloakMfa.skipOtp(executions);

        assertEquals(List.of(DIRECT_GRANT_OTP_SUB_FLOW), changed.stream().map(x -> x.get("displayName")).toList());
        assertEquals(DISABLED, named(executions, DIRECT_GRANT_OTP_SUB_FLOW).get("requirement"));
        // The username/password steps that actually authenticate the caller are untouched.
        assertEquals(REQUIRED, named(executions, "Username Validation").get("requirement"));
        assertEquals(REQUIRED, named(executions, "Password").get("requirement"));
    }

    /** Re-running against an already-converged direct grant flow asks for no admin API calls. */
    @Test
    public void reapplyingToTheDirectGrantFlowChangesNothing() {
        List<Map<String, Object>> executions = copiedDirectGrantFlow();
        KeyCloakMfa.skipOtp(executions);

        assertTrue(KeyCloakMfa.skipOtp(executions).isEmpty());
    }

    /** A flow with no OTP step in it is left alone rather than half-converted. */
    @Test
    public void aFlowWithoutOtpIsUnchanged() {
        List<Map<String, Object>> executions = new ArrayList<>();
        executions.add(execution("Cookie", "auth-cookie", ALTERNATIVE, 0, false));
        executions.add(execution("Username Password Form", "auth-username-password-form", REQUIRED, 0, false));

        assertTrue(KeyCloakMfa.requireOtp(executions).isEmpty());
        assertEquals(ALTERNATIVE, named(executions, "Cookie").get("requirement"));
    }
}
