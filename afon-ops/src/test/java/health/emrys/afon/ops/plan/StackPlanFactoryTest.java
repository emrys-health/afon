package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.StackFilter;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.steps.StackAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/**
 * Shows the steps derived from the components of each stack, for each action, and what else the components bring.
 */
class StackPlanFactoryTest {

    private final StackPlanFactory factory = new StackPlanFactory();

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    {
        properties.setValue(OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris.example");
        properties.setValue(AWS_JUPYTER_DOMAIN_NAME, "jupyter.example");
        properties.setValue(AZURE_POLARIS_DOMAIN_NAME, "polaris-azure.example");
        properties.setValue(AZURE_JUPYTER_DOMAIN_NAME, "jupyter-azure.example");
        properties.setValue(GCP_POLARIS_DOMAIN_NAME, "polaris-gcp.example");
        properties.setValue(AWS_EGRESS_IP, "");
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
    }

    /**
     * Creates each step named after the call that created it.
     */
    private final StepOperations ops = mock(StepOperations.class, (Answer<Object>) invocation -> {
        if (invocation.getMethod().getName().equals("properties")) {
            return properties;
        }
        if (invocation.getMethod().getName().equals("afonCatalogs")) {
            return new AfonCatalogs(properties);
        }
        if (invocation.getMethod().getReturnType() != Step.class) {
            return RETURNS_DEFAULTS.answer(invocation);
        }
        String call = String.format("%s(%s)", invocation.getMethod().getName(),
            Arrays.stream(invocation.getArguments()).map(String::valueOf).collect(Collectors.joining(",")));
        return new Step(call, call, () -> {});
    });

    private List<String> derived(StackAction action, String stackId) {
        return factory.steps(action, StackId.parse(stackId), ops, false).stream().map(Step::getName).toList();
    }

    private static String deleteStack(String stackId) {
        return String.format("deleteStacksIn(%s,delete_%s_stack,stack %s and the legacy stack)", stackId, stackId.replace('-', '_'), stackId);
    }

    @Test
    void deploysEachStackAfterDeletingTheStacksConflictingWithIt() {
        assertThat(derived(DEPLOY, "aws-keycloak")).containsExactly(
            deleteConflicting("aws-keycloak"), "deployStack(aws-keycloak,[AWS_KEYCLOAK_CERT_ARN])", "waitForKeyCloak()",
            "deployComplete(aws-keycloak)");
        assertThat(derived(DEPLOY, "aws-polaris")).containsExactly(
            deleteConflicting("aws-polaris"), "checkKeyCloakAnswers()", "deployStack(aws-polaris,[AWS_POLARIS_CERT_ARN])",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-polaris)");
        assertThat(derived(DEPLOY, "aws-full")).containsExactly(
            deleteConflicting("aws-full"),
            "deployStack(aws-full,[AWS_KEYCLOAK_CERT_ARN, AWS_POLARIS_CERT_ARN, AWS_JUPYTER_CERT_ARN])",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)");
        for (String cloud : List.of("azure", "gcp")) {
            String stack = cloud + "-polaris";
            assertThat(derived(DEPLOY, stack)).containsExactly(
                deleteConflicting(stack), "checkKeyCloakAnswers()", "deployStack(" + stack + ",[])",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "deployComplete(" + stack + ")");
        }
    }

    @Test
    void setsUpEachStackAfterDeployingItInOrderOfTheComponents() {
        assertThat(derived(SETUP, "aws-keycloak")).containsExactly(
            deleteConflicting("aws-keycloak"), "deployStack(aws-keycloak,[AWS_KEYCLOAK_CERT_ARN])", "waitForKeyCloak()",
            "deployComplete(aws-keycloak)", "configureOidcUsersFor(aws)", "setupComplete(aws-keycloak)");
        assertThat(derived(SETUP, "aws-polaris")).containsExactly(
            deleteConflicting("aws-polaris"), "checkKeyCloakAnswers()", "deployStack(aws-polaris,[AWS_POLARIS_CERT_ARN])",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-polaris)", "configureOidcUsersFor(aws)",
            "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)", "setupComplete(aws-polaris)");
        assertThat(derived(SETUP, "aws-full")).containsExactly(
            deleteConflicting("aws-full"),
            "deployStack(aws-full,[AWS_KEYCLOAK_CERT_ARN, AWS_POLARIS_CERT_ARN, AWS_JUPYTER_CERT_ARN])",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-full)", "configureOidcUsersFor(aws)",
            "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)", "runAwsSparkWritingTo(aws)",
            "setupComplete(aws-full)");
        for (String cloud : List.of("azure", "gcp")) {
            String stack = cloud + "-polaris";
            assertThat(derived(SETUP, stack)).containsExactly(
                deleteConflicting(stack), "checkKeyCloakAnswers()", "deployStack(" + stack + ",[])",
                "waitForPolarisAndKeycloakIn(" + cloud + ")", "deployComplete(" + stack + ")", "configureOidcUsersFor(" + cloud + ")",
                "createAllCatalogsFor(" + cloud + ")", "configurePolarisUsersOnAllCatalogsFor(" + cloud + ")", "setupComplete(" + stack + ")");
        }
    }

    @Test
    void deletesJustTheStackAndTheLegacyStackOrEveryStackOfTheCloud() {
        for (String stack : List.of("aws-keycloak", "aws-polaris", "aws-full", "azure-polaris", "gcp-polaris")) {
            assertThat(derived(DELETE, stack)).as(stack).containsExactly(deleteStack(stack), "deleteComplete(" + stack + ")");
        }
        for (String cloud : List.of("aws", "azure", "gcp")) {
            assertThat(derived(DELETE, cloud + "-all")).containsExactly("deleteStackIn(" + cloud + ")", "deleteComplete(" + cloud + "-all)");
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
        // stacks of different clouds hold nothing in common
        assertThat(factory.conflicts(StackId.parse("azure-polaris"), "aws-full", properties)).isFalse();
        assertThat(factory.conflicts(StackId.parse("gcp-polaris"), "azure-polaris", properties)).isFalse();
        // a stack conflicts with itself, the legacy stack of its cloud, and any stack that is not defined, as its resources are unknown
        for (StackId stackId : List.of(keycloak, polaris, full, StackId.parse("azure-polaris"), StackId.parse("gcp-polaris"))) {
            assertThat(factory.conflicts(stackId, stackId.toString(), properties)).as(stackId.toString()).isTrue();
            assertThat(factory.conflicts(stackId, stackId.cloud().toString(), properties)).as(stackId.toString()).isTrue();
            assertThat(factory.conflicts(stackId, stackId.cloud() + "-unknown", properties)).as(stackId.toString()).isTrue();
        }
    }

    @Test
    void fullStackRunsSideBySideWithKeyCloakStackWhenUsingExternalKeyCloak() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        properties.setValue(AWS_EGRESS_IP, "");
        assertThat(factory.conflicts(StackId.parse("aws-full"), "aws-keycloak", properties)).isFalse();
        assertThat(factory.conflicts(StackId.parse("aws-keycloak"), "aws-full", properties)).isFalse();
        // still holding the Polaris domain name
        assertThat(factory.conflicts(StackId.parse("aws-full"), "aws-polaris", properties)).isTrue();
    }

    @Test
    void deletingAStackNeverRefusesToDeleteTheLegacyStackOrItself() {
        List<StackFilter> filters = new ArrayList<>();
        factory.steps(DELETE, StackId.parse("aws-keycloak"), recordingFiltersIn(filters), false);

        StackFilter filter = filters.get(0);
        assertThat(filter.selects()).accepts("aws-keycloak", "aws").rejects("aws-full", "azure-polaris");
        assertThat(filter.refusal().apply("aws-keycloak")).isNull();
        assertThat(filter.refusal().apply("aws")).isNull();
    }

    private StepOperations recordingFiltersIn(List<StackFilter> filters) {
        return mock(StepOperations.class, (Answer<Object>) invocation -> {
            if (invocation.getMethod().getName().equals("properties")) {
                return properties;
            }
            Arrays.stream(invocation.getArguments()).filter(StackFilter.class::isInstance)
                  .forEach(arg -> filters.add((StackFilter) arg));
            return invocation.getMethod().getReturnType() == Step.class
                   ? new Step("step", "step", () -> {}) : RETURNS_DEFAULTS.answer(invocation);
        });
    }

    @Test
    void stacksComposedOfComponentsAreManagedLikeTheOthers() {
        assertThat(derived(DEPLOY, "azure-full")).containsExactly(
            deleteConflicting("azure-full"), "deployStack(azure-full,[])",
            "waitForPolarisAndKeycloakIn(azure)", "deployComplete(azure-full)");
        assertThat(derived(SETUP, "azure-full")).containsExactly(
            deleteConflicting("azure-full"), "deployStack(azure-full,[])",
            "waitForPolarisAndKeycloakIn(azure)", "deployComplete(azure-full)", "configureOidcUsersFor(azure)",
            "createAllCatalogsFor(azure)", "configurePolarisUsersOnAllCatalogsFor(azure)", "setupComplete(azure-full)");
        // the Spark write is checked on AWS only, so far, and aws-trino has everything aws-full has but the monitoring, KeyCloak
        // included, so it checks no other
        assertThat(derived(SETUP, "aws-trino")).containsExactly(
            deleteConflicting("aws-trino"), "deployStack(aws-trino,[AWS_KEYCLOAK_CERT_ARN, AWS_POLARIS_CERT_ARN, AWS_JUPYTER_CERT_ARN])",
            "waitForPolarisAndKeycloakIn(aws)", "deployComplete(aws-trino)", "configureOidcUsersFor(aws)",
            "createAllCatalogsFor(aws)", "configurePolarisUsersOnAllCatalogsFor(aws)", "runAwsSparkWritingTo(aws)",
            "setupComplete(aws-trino)");
    }

    @Test
    void snowflakeStackIsSetUpAsTheFullStackSinceSnowflakeDeploysAndConfiguresNothing() {
        List<String> full = derived(SETUP, "aws-full");
        List<String> snowflake = derived(SETUP, "aws-snowflake");
        assertThat(snowflake).hasSameSizeAs(full);
        assertThat(snowflake).containsExactlyElementsOf(full.stream().map(step -> step.replace("aws-full", "aws-snowflake").replace("aws_full", "aws_snowflake")).toList());
        // it holds what the full stack holds, so they conflict
        assertThat(factory.conflicts(StackId.parse("aws-snowflake"), "aws-full", properties)).isTrue();
    }

    @Test
    void stacksRunTheScenariosOfTheirComponentsInOrderOfListing() {
        // the scenarios of the components, in the order of the components: Spark's, then Snowflake's
        assertThat(factory.scenarios(StackId.parse("aws-snowflake"), properties).keySet())
            .containsExactly("external-azure", "reading-aws", "reading-azure", "reading-gcp", "writing-azure");
        assertThat(factory.scenarios(StackId.parse("aws-full"), properties).keySet()).containsExactly("external-azure");
        assertThat(factory.scenarios(StackId.parse("aws-spark"), properties).keySet()).containsExactly("external-azure");
        // no Spark, so nothing to defer to the Azure Polaris
        assertThat(factory.scenarios(StackId.parse("aws-polaris"), properties)).isEmpty();
        assertThat(factory.scenarios(StackId.parse("aws-keycloak"), properties)).isEmpty();
        assertThat(factory.scenarios(StackId.parse("aws-all"), properties)).isEmpty();
        assertThat(factory.scenarios(StackId.parse("azure-full"), properties)).isEmpty();
    }

    @Test
    void externalAzureChecksTheAzurePolarisBeforeFederatingToIt() {
        properties.setValue(AZURE_POLARIS_DOMAIN_NAME, "polaris-azure.example");
        assertThat(factory.scenarioSteps(StackId.parse("aws-full"), "external-azure", ops).stream().map(Step::getName).toList())
            .hasSize(4)
            .satisfiesExactly(
                step -> assertThat(step).isEqualTo("checkPolarisAnswers(azure)"),
                step -> assertThat(step).startsWith("deleteExternalPolarisTableFor(azure)"),
                step -> assertThat(step).startsWith("createExternalCatalogFor(azure)"),
                step -> assertThat(step).startsWith("runAwsSparkDeferringToAzurePolarisHostingExternalCatalog"));
    }

    @Test
    void scenarioStepsAreOnlyCreatedWhenRun() {
        assertThat(factory.scenarioSteps(StackId.parse("aws-snowflake"), "reading-azure", ops).stream().map(Step::getName).toList())
            .hasSize(2).first().asString().startsWith("runAwsSparkWritingTo(azure)");
        assertThat(factory.scenarioSteps(StackId.parse("aws-snowflake"), "reading-aws", ops)).hasSize(1);
        assertThat(factory.scenarioSteps(StackId.parse("aws-snowflake"), "writing-azure", ops).stream().map(Step::getName).toList())
            .hasSize(4).first().asString().startsWith("snowflakeWritingTo(azure)");
        assertThatThrownBy(() -> factory.scenarioSteps(StackId.parse("aws-full"), "reading-aws", ops))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Stack 'aws-full' has no scenario 'reading-aws'");
    }

    @Test
    void checksTheCertificatesOfTheLoadBalancersOfTheStack() {
        // aws-keycloak checks AWS_KEYCLOAK_CERT_ARN, the Polaris stacks AWS_POLARIS_CERT_ARN, aws-full all three, and the other
        // clouds have no ACM certificates
        assertThat(deployStepOf("aws-keycloak")).isEqualTo("deployStack(aws-keycloak,[AWS_KEYCLOAK_CERT_ARN])");
        assertThat(deployStepOf("aws-polaris")).isEqualTo("deployStack(aws-polaris,[AWS_POLARIS_CERT_ARN])");
        assertThat(deployStepOf("aws-full")).isEqualTo(
            "deployStack(aws-full,[AWS_KEYCLOAK_CERT_ARN, AWS_POLARIS_CERT_ARN, AWS_JUPYTER_CERT_ARN])");
        assertThat(deployStepOf("azure-polaris")).isEqualTo("deployStack(azure-polaris,[])");
        assertThat(deployStepOf("gcp-polaris")).isEqualTo("deployStack(gcp-polaris,[])");
    }

    private String deployStepOf(String stackId) {
        return derived(DEPLOY, stackId).stream().filter(step -> step.startsWith("deployStack(")).findFirst().orElseThrow();
    }

    @Test
    void providesTheStepsOfEveryActionOnEveryStackDefinedAndOnlyDeletesEveryStackOfACloud() {
        assertThat(factory.supportedActions().keySet().stream().map(StackId::toString)).containsExactly(
            "aws-all", "aws-full", "aws-jupyter", "aws-keycloak", "aws-polaris", "aws-snowflake", "aws-spark", "aws-trino",
            "azure-all", "azure-full", "azure-polaris", "gcp-all", "gcp-polaris");
        factory.supportedActions().forEach((stackId, actions) -> assertThat(actions).as(stackId.toString())
            .isEqualTo(stackId.isAll() ? List.of(DELETE) : List.of(DELETE, DEPLOY, SETUP)));

        assertThat(factory.stepsProviderFor(DEPLOY, StackId.parse("aws-all"), false)).isNull();
        assertThat(factory.stepsProviderFor(DEPLOY, StackId.parse("aws-unknown"), false)).isNull();
        assertThat(factory.stepsProviderFor(SETUP, StackId.parse("azure-full"), false)).isNotNull();
        assertThatThrownBy(() -> factory.steps(SETUP, StackId.parse("aws-all"), ops, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported action 'setup' on stack 'aws-all'");
    }

    @Test
    void aStackWithItsOwnKeyCloakDoesNotCheckAnotherOne() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(derived(DEPLOY, "aws-full")).doesNotContain("checkKeyCloakAnswers()");

        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(derived(DEPLOY, "aws-full")).contains("checkKeyCloakAnswers()");
        // the stack no longer holds the KeyCloak domain, so no longer conflicts with the stack that does
        properties.setValue(AWS_EGRESS_IP, "");
        assertThat(factory.conflicts(StackId.parse("aws-full"), "aws-keycloak", properties)).isFalse();
    }

    @Test
    void everyComponentOfEveryStackDeclaresWhatItBringsToTheActions() {
        ComponentPlanRegistry registry = ComponentPlanRegistry.standard();
        for (StackDefinition definition : StackDefinitions.all()) {
            for (StackComponent component : definition.componentsFor(properties)) {
                assertThat(registry.of(component)).as("%s of %s", component.getClass().getSimpleName(), definition.id()).isNotNull();
            }
        }
        assertThatThrownBy(() -> new ComponentPlanRegistry().of(new health.emrys.afon.cdk.components.PolarisComponent()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No manage-stack plan declared for PolarisComponent");
    }

    @Test
    void refusesToDeleteAnotherConflictingStackUnlessReplacing() {
        List<StackFilter> filters = new ArrayList<>();
        StepOperations recording = recordingFiltersIn(filters);
        properties.setValue(AWS_EGRESS_IP, "");

        factory.steps(DEPLOY, StackId.parse("aws-polaris"), recording, false);
        factory.steps(DEPLOY, StackId.parse("aws-polaris"), recording, true);

        StackFilter keeping = filters.get(0);
        StackFilter replacing = filters.get(1);
        assertThat(keeping.refusal().apply("aws-full").reason())
            .isEqualTo("stack aws-full is running and already deploys Polaris at polaris.example (AWS_POLARIS_DOMAIN_NAME)");
        assertThat(keeping.refusal().apply("aws-polaris")).isNull();
        assertThat(replacing.refusal().apply("aws-full")).isNull();

        // it names every resource held in common, and how to delete the stack instead
        properties.setValue(AWS_EGRESS_IP, "203.0.113.7");
        filters.clear();
        factory.steps(DEPLOY, StackId.parse("aws-polaris"), recording, false);
        assertThat(filters.get(0).refusal().apply("aws-full")).isEqualTo(new StackFilter.Refusal(
            "stack aws-full is running and already deploys Polaris at polaris.example (AWS_POLARIS_DOMAIN_NAME), "
            + "and holds the pinned egress IP 203.0.113.7 (AWS_EGRESS_IP)",
            "afon-cli.sh manage-stack delete aws-full"));
        assertThat(filters.get(0).refusal().apply("aws-keycloak")).isNull();
        assertThat(filters.get(0).refusal().apply("aws").reason()).startsWith("the legacy stack aws");
    }

    private static String deleteConflicting(String stackId) {
        return String.format("deleteStacksIn(%s,delete_stacks_conflicting_with_%s,stacks conflicting with %s)",
                             stackId, stackId.replace('-', '_'), stackId);
    }
}
