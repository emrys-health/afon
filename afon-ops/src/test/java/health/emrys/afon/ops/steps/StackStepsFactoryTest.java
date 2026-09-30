package health.emrys.afon.ops.steps;

import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.ops.steps.StackAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

class StackStepsFactoryTest {

    private final StackStepsFactory factory = new StackStepsFactory();

    /**
     * The calls made to create the steps, in order, eg {@code deleteStackIn(aws)}.
     */
    private final List<String> calls = new ArrayList<>();

    /**
     * The filters of the stacks to delete given to the calls, by description.
     */
    private final Map<String, StackFilter> filters = new HashMap<>();

    /**
     * Creates each step named after the call that created it, recording the call.
     */
    private final StepOperations ops = mock(StepOperations.class, (Answer<Object>) invocation -> {
        if (invocation.getMethod().getReturnType() != Step.class) {
            return RETURNS_DEFAULTS.answer(invocation);
        }
        Arrays.stream(invocation.getArguments())
              .filter(StackFilter.class::isInstance)
              .map(StackFilter.class::cast)
              .forEach(filter -> filters.put(filter.description(), filter));
        String call = String.format("%s(%s)", invocation.getMethod().getName(),
            Arrays.stream(invocation.getArguments()).map(String::valueOf).collect(Collectors.joining(",")));
        calls.add(call);
        return new Step(call, call, () -> {});
    });

    @Test
    void canProvideStepsToDeployStacks() {
        assertSteps(DEPLOY, "aws-full",
            deleteConflicting("aws-full"), "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws)");
        assertSteps(DEPLOY, "aws-keycloak",
            deleteConflicting("aws-keycloak"), "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "deployComplete(aws)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(DEPLOY, cloud + "-polaris",
                deleteConflicting(cloud + "-polaris"), "checkKeyCloakAnswers()", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "deployComplete(" + cloud + ")");
        }
    }

    @Test
    void canProvideStepsToSetupStacks() {
        assertSteps(SETUP, "aws-full",
            deleteConflicting("aws-full"), "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws)",
            "configureOidcUsersFor(aws)", "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)",
            "runAwsSparkWritingTo(aws)", "setupComplete(aws)");
        assertSteps(SETUP, "aws-keycloak",
            deleteConflicting("aws-keycloak"), "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "configureOidcUsersFor(aws)",
            "setupComplete(aws)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(SETUP, cloud + "-polaris",
                deleteConflicting(cloud + "-polaris"), "checkKeyCloakAnswers()", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "configureOidcUsersFor(" + cloud + ")",
                "createAllCatalogsFor(" + cloud + ")", "configurePolarisUsersOnAllCatalogsFor(" + cloud + ")",
                "setupComplete(" + cloud + ")");
        }
    }

    @Test
    void canProvideStepsToDeleteJustTheStackAndTheLegacyStack() {
        for (String stackId : List.of("aws-full", "aws-keycloak", "aws-polaris", "azure-polaris", "gcp-polaris")) {
            String cloud = StackId.parse(stackId).cloud().toString();
            String filter = String.format("stack %s and the legacy stack", stackId);
            assertSteps(DELETE, stackId,
                String.format("deleteStacksIn(%s,delete_%s_stack,%s)", cloud, stackId.replace('-', '_'), filter),
                "deleteComplete(" + cloud + ")");
            assertThat(filters.get(filter).selects())
                .accepts(stackId, cloud)
                .rejects(cloud + "-other", "aws-all");
        }
    }

    @Test
    void canProvideStepsToDeleteEveryStackInCloud() {
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(DELETE, cloud + "-all", "deleteStackIn(" + cloud + ")", "deleteComplete(" + cloud + ")");
        }
    }

    @Test
    void stacksConflictWhenHoldingCommonExclusiveResource() {
        StackId keycloak = StackId.parse("aws-keycloak");
        StackId polaris = StackId.parse("aws-polaris");
        StackId full = StackId.parse("aws-full");
        // KeyCloak and Polaris run side by side, as Polaris uses KeyCloak rather than deploying its own
        assertThat(factory.conflicts(polaris, "aws-keycloak")).isFalse();
        assertThat(factory.conflicts(keycloak, "aws-polaris")).isFalse();
        // the full stack deploys its own KeyCloak and Polaris
        assertThat(factory.conflicts(full, "aws-keycloak")).isTrue();
        assertThat(factory.conflicts(full, "aws-polaris")).isTrue();
        assertThat(factory.conflicts(keycloak, "aws-full")).isTrue();
        assertThat(factory.conflicts(polaris, "aws-full")).isTrue();
        // a stack conflicts with itself, the legacy stack and any stack whose resources are unknown
        for (StackId stackId : List.of(keycloak, polaris, full)) {
            assertThat(factory.conflicts(stackId, stackId.toString())).isTrue();
            assertThat(factory.conflicts(stackId, "aws")).isTrue();
            assertThat(factory.conflicts(stackId, "aws-jupyter")).isTrue();
        }
        // the deploy steps delete the conflicting stacks
        factory.steps(DEPLOY, polaris, ops);
        assertThat(filters.get("stacks conflicting with aws-polaris").selects())
            .accepts("aws", "aws-polaris", "aws-full", "aws-jupyter")
            .rejects("aws-keycloak");
    }

    @Test
    void onlyStacksNotDeployingTheirOwnKeyCloakUseExternalKeyCloak() {
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-polaris"))).isTrue();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("azure-polaris"))).isTrue();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-keycloak"))).isFalse();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-full"))).isFalse();
    }

    @Test
    void canListSupportedActionsOfStacks() {
        assertThat(factory.supportedActions().keySet().stream().map(StackId::toString))
            .containsExactly("aws-all", "aws-full", "aws-keycloak", "aws-polaris", "azure-all", "azure-polaris",
                             "gcp-all", "gcp-polaris");
        factory.supportedActions().forEach((stackId, actions) -> assertThat(actions).as(stackId.toString())
            .isEqualTo(stackId.isAll() ? List.of(DELETE) : List.of(DELETE, DEPLOY, SETUP)));
    }

    @Test
    void cannotProvideStepsOfUnknownStack() {
        StackId unknown = new StackId(AZURE, "full");
        assertThat(factory.stepsProviderFor(DEPLOY, unknown)).isNull();
        assertThat(factory.stepsProviderFor(DELETE, unknown)).isNull();
        assertThatThrownBy(() -> factory.steps(DEPLOY, unknown, ops))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported action 'deploy' on stack 'azure-full'");
    }

    @Test
    void canParseActionCaseInsensitively() {
        assertThat(StackAction.of("deploy")).isEqualTo(DEPLOY);
        assertThat(StackAction.of("SETUP")).isEqualTo(SETUP);
        assertThat(StackAction.of("destroy")).isNull();
        assertThat(DELETE).hasToString("delete");
    }

    private static String deleteConflicting(String stackId) {
        return String.format("deleteStacksIn(%s,delete_stacks_conflicting_with_%s,stacks conflicting with %s)",
                             StackId.parse(stackId).cloud(), stackId.replace('-', '_'), stackId);
    }

    private void assertSteps(StackAction action, String stackId, String... expected) {
        calls.clear();
        List<String> steps = factory.steps(action, StackId.parse(stackId), ops).stream().map(Step::getName).toList();
        assertThat(steps).as("%s %s", action, stackId).containsExactly(expected);
        // the steps are created in the order they run, as StepOperations logs as it creates them
        assertThat(calls).as("%s %s calls", action, stackId).containsExactly(expected);
    }
}
