package health.emrys.afon.ops.steps;

import java.util.Arrays;
import java.util.List;

/**
 * The actions executed in relation to a stack, the verbs of {@code manage-stack}: {@code deploy} just deploys it (waiting
 * for DNS to propagate), {@code setup} also configures it (eg the OIDC users and the Polaris catalogs), {@code delete}
 * deletes it, and {@code run} runs a scenario on a stack that is set up, or a flow, ie a named list of actions and
 * scenarios on several stacks.
 * <p>
 * The first three derive their steps from the stack, see {@link #derivesSteps}, while a scenario is named by the components
 * of the stack, and a flow by the {@code StackFlows}.
 */
public enum StackAction {
    DELETE, DEPLOY, SETUP, RUN;

    /**
     * @return whether the steps of the action on a stack are derived from the stack alone, ie it is {@code delete},
     * {@code deploy} or {@code setup}
     */
    public boolean derivesSteps() {
        return this == DELETE || this == DEPLOY || this == SETUP;
    }

    /**
     * @return the actions deriving their steps from the stack, in order, see {@link #derivesSteps}
     */
    public static List<StackAction> derivingSteps() {
        return Arrays.stream(values()).filter(StackAction::derivesSteps).toList();
    }

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
