package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
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
     * The config the steps are created with: the templates, with the exclusive resources set.
     */
    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    {
        properties.setValue(OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris.example");
        properties.setValue(AWS_JUPYTER_DOMAIN_NAME, "jupyter.example");
        properties.setValue(AZURE_POLARIS_DOMAIN_NAME, "polaris-azure.example");
        properties.setValue(GCP_POLARIS_DOMAIN_NAME, "polaris-gcp.example");
        properties.setValue(AWS_EGRESS_IP, "");
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
    }

    /**
     * The filters of the stacks to delete given to the calls, by description.
     */
    private final Map<String, StackFilter> filters = new HashMap<>();

    /**
     * Creates each step named after the call that created it, recording the call.
     */
    private final StepOperations ops = mock(StepOperations.class, (Answer<Object>) invocation -> {
        if (invocation.getMethod().getName().equals("properties")) {
            return properties;
        }
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
            deleteConflicting("aws-full"), "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)");
        assertSteps(DEPLOY, "aws-keycloak",
            deleteConflicting("aws-keycloak"), "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "deployComplete(aws-keycloak)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(DEPLOY, cloud + "-polaris",
                deleteConflicting(cloud + "-polaris"), "checkKeyCloakAnswers()", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "deployComplete(" + cloud + "-polaris)");
        }
    }

    @Test
    void canProvideStepsToSetupStacks() {
        assertSteps(SETUP, "aws-full",
            deleteConflicting("aws-full"), "deployFullStackIn(aws)", "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)",
            "configureOidcUsersFor(aws)", "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)",
            "runAwsSparkWritingTo(aws)", "setupComplete(aws-full)");
        assertSteps(SETUP, "aws-keycloak",
            deleteConflicting("aws-keycloak"), "deployKeycloakStackIn(aws)", "waitForKeyCloak()", "configureOidcUsersFor(aws)",
            "setupComplete(aws-keycloak)");
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(SETUP, cloud + "-polaris",
                deleteConflicting(cloud + "-polaris"), "checkKeyCloakAnswers()", "deployPolarisStackIn(" + cloud + ")",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "configureOidcUsersFor(" + cloud + ")",
                "createAllCatalogsFor(" + cloud + ")", "configurePolarisUsersOnAllCatalogsFor(" + cloud + ")",
                "setupComplete(" + cloud + "-polaris)");
        }
    }

    @Test
    void canProvideStepsToDeleteJustTheStackAndTheLegacyStack() {
        for (String stackId : List.of("aws-full", "aws-keycloak", "aws-polaris", "azure-polaris", "gcp-polaris")) {
            String cloud = StackId.parse(stackId).cloud().toString();
            String filter = String.format("stack %s and the legacy stack", stackId);
            assertSteps(DELETE, stackId,
                String.format("deleteStacksIn(%s,delete_%s_stack,%s)", stackId, stackId.replace('-', '_'), filter),
                "deleteComplete(" + stackId + ")");
            assertThat(filters.get(filter).selects())
                .accepts(stackId, cloud)
                .rejects(cloud + "-other", "aws-all");
        }
    }

    @Test
    void canProvideStepsToDeleteEveryStackInCloud() {
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertSteps(DELETE, cloud + "-all", "deleteStackIn(" + cloud + ")", "deleteComplete(" + cloud + "-all)");
        }
    }

    @Test
    void stacksConflictWhenHoldingCommonExclusiveResource() {
        StackId keycloak = StackId.parse("aws-keycloak");
        StackId polaris = StackId.parse("aws-polaris");
        StackId full = StackId.parse("aws-full");
        // KeyCloak and Polaris run side by side, as Polaris uses KeyCloak rather than deploying its own
        assertThat(factory.conflicts(polaris, "aws-keycloak", properties)).isFalse();
        assertThat(factory.conflicts(keycloak, "aws-polaris", properties)).isFalse();
        // the full stack deploys its own KeyCloak and Polaris
        assertThat(factory.conflicts(full, "aws-keycloak", properties)).isTrue();
        assertThat(factory.conflicts(full, "aws-polaris", properties)).isTrue();
        assertThat(factory.conflicts(keycloak, "aws-full", properties)).isTrue();
        assertThat(factory.conflicts(polaris, "aws-full", properties)).isTrue();
        // a stack conflicts with itself, the legacy stack and any stack whose resources are unknown
        for (StackId stackId : List.of(keycloak, polaris, full)) {
            assertThat(factory.conflicts(stackId, stackId.toString(), properties)).isTrue();
            assertThat(factory.conflicts(stackId, "aws", properties)).isTrue();
            assertThat(factory.conflicts(stackId, "aws-jupyter", properties)).isTrue();
        }
        // the deploy steps delete the conflicting stacks
        factory.steps(DEPLOY, polaris, ops);
        assertThat(filters.get("stacks conflicting with aws-polaris").selects())
            .accepts("aws", "aws-polaris", "aws-full", "aws-jupyter")
            .rejects("aws-keycloak");
    }

    @Test
    void fullStackRunsSideBySideWithKeyCloakStackWhenUsingExternalKeyCloak() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        StackId keycloak = StackId.parse("aws-keycloak");
        StackId full = StackId.parse("aws-full");
        assertThat(factory.conflicts(full, "aws-keycloak", properties)).isFalse();
        assertThat(factory.conflicts(keycloak, "aws-full", properties)).isFalse();
        // still holding the Polaris domain name
        assertThat(factory.conflicts(full, "aws-polaris", properties)).isTrue();
    }

    @Test
    void onlyStacksNotDeployingTheirOwnKeyCloakUseExternalKeyCloak() {
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-polaris"), properties)).isTrue();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("azure-polaris"), properties)).isTrue();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-keycloak"), properties)).isFalse();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-full"), properties)).isFalse();
        // unless configured to use the external KeyCloak
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-full"), properties)).isTrue();
        assertThat(factory.usesExternalKeyCloak(StackId.parse("aws-keycloak"), properties)).isFalse();
    }

    @Test
    void canProvideStepsToDeployFullStackUsingExternalKeyCloak() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertSteps(DEPLOY, "aws-full",
            deleteConflicting("aws-full"), "checkKeyCloakAnswers()", "deployFullStackIn(aws)",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)");
        assertThat(filters.get("stacks conflicting with aws-full").selects()).rejects("aws-keycloak");
        assertSteps(SETUP, "aws-full",
            deleteConflicting("aws-full"), "checkKeyCloakAnswers()", "deployFullStackIn(aws)",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)", "configureOidcUsersFor(aws)",
            "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)", "runAwsSparkWritingTo(aws)",
            "setupComplete(aws-full)");
    }

    @Test
    void refusesToDeleteOtherConflictingStacksUnlessReplacing() {
        factory.steps(DEPLOY, StackId.parse("aws-full"), ops);
        Function<String, StackFilter.Refusal> refusal = filters.get("stacks conflicting with aws-full").refusal();
        assertThat(filters.get("stacks conflicting with aws-full").deploying()).isEqualTo(StackId.parse("aws-full"));
        // the stack itself is replaced
        assertThat(refusal.apply("aws-full")).isNull();
        assertThat(refusal.apply("aws-keycloak")).isEqualTo(new StackFilter.Refusal(
            "stack aws-keycloak is running and already deploys KeyCloak at oauth.example (OIDC_DOMAIN_NAME)",
            "afon-cli.sh manage-stack delete aws-keycloak"));
        assertThat(refusal.apply("aws-polaris")).isEqualTo(new StackFilter.Refusal(
            "stack aws-polaris is running and already deploys Polaris at polaris.example (AWS_POLARIS_DOMAIN_NAME)",
            "afon-cli.sh manage-stack delete aws-polaris"));
        assertThat(refusal.apply("aws-jupyter")).isEqualTo(new StackFilter.Refusal(
            "stack aws-jupyter is running, whose resources are unknown", null));
        // deleting aws-full deletes the legacy stack too
        assertThat(refusal.apply("aws").reason()).startsWith("the legacy stack aws");
        assertThat(refusal.apply("aws").deleteCommand()).isEqualTo("afon-cli.sh manage-stack delete aws-full");

        filters.clear();
        factory.steps(SETUP, StackId.parse("aws-full"), ops, true);
        Function<String, StackFilter.Refusal> replacing = filters.get("stacks conflicting with aws-full").refusal();
        for (String stackName : List.of("aws-full", "aws-keycloak", "aws-polaris", "aws-jupyter", "aws")) {
            assertThat(replacing.apply(stackName)).as(stackName).isNull();
        }
    }

    @Test
    void pinnedEgressIpIsOnlyHeldWhenSet() {
        StackId polaris = StackId.parse("aws-polaris");
        factory.steps(DEPLOY, polaris, ops);
        assertThat(filters.get("stacks conflicting with aws-polaris").refusal().apply("aws-full").reason())
            .isEqualTo("stack aws-full is running and already deploys Polaris at polaris.example (AWS_POLARIS_DOMAIN_NAME)");

        properties.setValue(AWS_EGRESS_IP, "203.0.113.7");
        filters.clear();
        factory.steps(DEPLOY, polaris, ops);
        assertThat(filters.get("stacks conflicting with aws-polaris").refusal().apply("aws-full").reason())
            .isEqualTo("stack aws-full is running and already deploys Polaris at polaris.example (AWS_POLARIS_DOMAIN_NAME), "
                       + "and holds the pinned egress IP 203.0.113.7 (AWS_EGRESS_IP)");
    }

    @Test
    void deletingStackNeverRefuses() {
        factory.steps(DELETE, StackId.parse("aws-keycloak"), ops);
        Function<String, StackFilter.Refusal> refusal = filters.get("stack aws-keycloak and the legacy stack").refusal();
        assertThat(refusal.apply("aws-keycloak")).isNull();
        assertThat(refusal.apply("aws")).isNull();
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
                             stackId, stackId.replace('-', '_'), stackId);
    }

    private void assertSteps(StackAction action, String stackId, String... expected) {
        calls.clear();
        List<String> steps = factory.steps(action, StackId.parse(stackId), ops).stream().map(Step::getName).toList();
        assertThat(steps).as("%s %s", action, stackId).containsExactly(expected);
        // the steps are created in the order they run, as StepOperations logs as it creates them
        assertThat(calls).as("%s %s calls", action, stackId).containsExactly(expected);
    }
}
