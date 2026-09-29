package health.emrys.afon.ops.steps;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
     * Creates each step named after the call that created it, recording the call.
     */
    private final StepOperations ops = mock(StepOperations.class, (Answer<Object>) invocation -> {
        if (invocation.getMethod().getReturnType() != Step.class) {
            return RETURNS_DEFAULTS.answer(invocation);
        }
        String call = String.format("%s(%s)", invocation.getMethod().getName(),
            Arrays.stream(invocation.getArguments()).map(String::valueOf).collect(Collectors.joining(",")));
        calls.add(call);
        return new Step(call, call, () -> {});
    });

    @Test
    void canProvideStepsToDeployStacks() {
        assertSteps(DEPLOY, "aws-full",
            "deleteStackIn(aws)", "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws)");
        assertSteps(DEPLOY, "aws-keycloak",
            "deleteStackIn(aws)", "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "deployComplete(aws)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(DEPLOY, cloud + "-polaris",
                "deleteStackIn(" + cloud + ")", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "deployComplete(" + cloud + ")");
        }
    }

    @Test
    void canProvideStepsToSetupStacks() {
        assertSteps(SETUP, "aws-full",
            "deleteStackIn(aws)", "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws)",
            "configureOidcUsersFor(aws)", "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)",
            "runAwsSparkWritingTo(aws)", "setupComplete(aws)");
        assertSteps(SETUP, "aws-keycloak",
            "deleteStackIn(aws)", "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "configureOidcUsersFor(aws)",
            "setupComplete(aws)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(SETUP, cloud + "-polaris",
                "deleteStackIn(" + cloud + ")", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "configureOidcUsersFor(" + cloud + ")",
                "createAllCatalogsFor(" + cloud + ")", "configurePolarisUsersOnAllCatalogsFor(" + cloud + ")",
                "setupComplete(" + cloud + ")");
        }
    }

    @Test
    void canProvideStepsToDeleteStacksInTheirCloud() {
        for (String stackId : List.of("aws-full", "aws-keycloak", "aws-polaris")) {
            assertSteps(DELETE, stackId, "deleteStackIn(aws)", "deleteComplete(aws)");
        }
        assertSteps(DELETE, "azure-polaris", "deleteStackIn(azure)", "deleteComplete(azure)");
        assertSteps(DELETE, "gcp-polaris", "deleteStackIn(gcp)", "deleteComplete(gcp)");
    }

    @Test
    void canListSupportedActionsOfStacks() {
        assertThat(factory.supportedActions().keySet().stream().map(StackId::toString))
            .containsExactly("aws-full", "aws-keycloak", "aws-polaris", "azure-polaris", "gcp-polaris");
        assertThat(factory.supportedActions().values()).allMatch(actions -> actions.equals(List.of(DELETE, DEPLOY, SETUP)));
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
    void canParseStackId() {
        assertThat(StackId.parse("aws-keycloak")).isEqualTo(new StackId(AWS, "keycloak"));
        assertThat(StackId.parse("GCP-Polaris")).isEqualTo(new StackId(GCP, "polaris"));
        assertThat(new StackId(AZURE, "polaris")).hasToString("azure-polaris");
        for (String invalid : List.of("aws", "aws-", "-keycloak", "mars-keycloak")) {
            assertThatThrownBy(() -> StackId.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void canParseActionCaseInsensitively() {
        assertThat(StackAction.of("deploy")).isEqualTo(DEPLOY);
        assertThat(StackAction.of("SETUP")).isEqualTo(SETUP);
        assertThat(StackAction.of("destroy")).isNull();
        assertThat(DELETE).hasToString("delete");
    }

    private void assertSteps(StackAction action, String stackId, String... expected) {
        calls.clear();
        List<String> steps = factory.steps(action, StackId.parse(stackId), ops).stream().map(Step::getName).toList();
        assertThat(steps).as("%s %s", action, stackId).containsExactly(expected);
        // the steps are created in the order they run, as StepOperations logs as it creates them
        assertThat(calls).as("%s %s calls", action, stackId).containsExactly(expected);
    }
}
