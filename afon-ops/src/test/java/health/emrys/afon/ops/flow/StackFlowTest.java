package health.emrys.afon.ops.flow;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.flow.FlowItem.delete;
import static health.emrys.afon.ops.flow.FlowItem.deploy;
import static health.emrys.afon.ops.flow.FlowItem.run;
import static health.emrys.afon.ops.flow.FlowItem.setup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

class StackFlowTest {

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

    @Test
    void definedFlowsAreValid() {
        StackFlows.all().forEach(flow -> flow.validate(factory, properties));
        assertThat(StackFlows.all()).extracting(StackFlow::name).contains("cross-cloud-azure");
    }

    @Test
    void flowNamesAreNeverStackIds() {
        StackFlows.all().forEach(flow -> assertThat(new StackPlanFactory().supportedActions().keySet().stream().map(Object::toString))
            .doesNotContain(flow.name()));
    }

    @Test
    void rejectsUnknownStackScenarioAndUnsupportedAction() {
        assertThatThrownBy(() -> StackFlow.of("f", setup("aws-full"), run("aws-full", "nope")).validate(factory, properties))
            .hasMessageContaining("no scenario 'nope'");
        assertThatThrownBy(() -> StackFlow.of("f", run("aws-unknown", "x")).validate(factory, properties))
            .hasMessageContaining("no such stack");
        assertThatThrownBy(() -> StackFlow.of("f", setup("aws-all")).validate(factory, properties))
            .hasMessageContaining("unsupported action");
        assertThatThrownBy(() -> StackFlow.of("f", setup("aws-full"), delete("aws-full"), run("aws-full", "external-azure"))
            .validate(factory, properties)).hasMessageContaining("deleted by an earlier item");
        assertThatThrownBy(() -> StackFlow.of("f", delete("aws-all"), run("aws-full", "external-azure"))
            .validate(factory, properties)).hasMessageContaining("deleted by an earlier item");
    }

    @Test
    void canBeJustAChainOfRunsOnStacksAlreadySetUp() {
        StackFlow.of("f", run("aws-snowflake", "reading-aws"), run("aws-snowflake", "reading-gcp")).validate(factory, properties);
        StackFlow.of("f", delete("aws-full"), setup("aws-full"), run("aws-full", "external-azure")).validate(factory, properties);
    }

    @Test
    void rejectsLaterStackDeletingEarlierOne() {
        // both hold the AWS Polaris domain name
        assertThatThrownBy(() -> StackFlow.of("f", setup("aws-full"), setup("aws-polaris")).validate(factory, properties))
            .hasMessageContaining("would delete aws-full");
        StackFlow.of("f", setup("aws-full"), delete("aws-full"), setup("aws-polaris")).validate(factory, properties);
    }

    @Test
    void stepsAreInTheOrderOfTheItems() {
        StackFlow flow = StackFlows.get("cross-cloud-azure", factory, properties);
        List<FlowStep> steps = new FlowPlanFactory(factory).steps(flow, ops(), false);
        List<Integer> items = steps.stream().map(FlowStep::itemIndex).toList();
        assertThat(items).isSorted().containsSubsequence(1, 2, 3).startsWith(1).endsWith(3);
        assertThat(steps.stream().map(s -> s.item().label()).distinct())
            .containsExactly("setup aws-full", "setup azure-polaris", "run aws-full external-azure");
        assertThat(steps.stream().map(FlowStep::qualifiedName)).doesNotHaveDuplicates();
    }

    @Test
    void snowflakeReadingAllRunsEveryReadingOfTheStackInOrder() {
        StackFlow flow = StackFlows.get("snowflake-reading-all", factory, properties);
        assertThat(flow.items()).containsExactly(
            run("aws-snowflake", "reading-aws"), run("aws-snowflake", "reading-azure"), run("aws-snowflake", "reading-gcp"));
        assertThat(factory.scenarios(flow.items().get(0).stackId(), properties).keySet())
            .contains("reading-aws", "reading-azure", "reading-gcp");
        List<FlowStep> steps = new FlowPlanFactory(factory).steps(flow, ops(), false);
        assertThat(steps.stream().map(FlowStep::itemIndex).distinct()).containsExactly(1, 2, 3);
    }

    @Test
    void failureReportsItemsNotRunAndResumesFromTheQualifiedStepName() {
        List<String> ran = new ArrayList<>();
        boolean[] fail = {true};
        List<FlowStep> steps = List.of(
            new FlowStep(1, setup("aws-full"), new Step("a", "a", () -> ran.add("a"))),
            new FlowStep(2, setup("azure-polaris"), new Step("a", "a", () -> {
                if (fail[0]) {
                    throw new IllegalStateException("boom");
                }
                ran.add("b");
            })),
            new FlowStep(3, run("aws-full", "external-azure"), new Step("c", "c", () -> ran.add("c"))));
        FlowRunner runner = new FlowRunner();
        assertThatThrownBy(() -> runner.run("f", steps, null))
            .isInstanceOf(FlowRunner.FlowFailedException.class)
            .hasMessageContaining("2:a")
            .hasMessageContaining("Items not run: run aws-full external-azure")
            .hasMessageContaining("run f 2:a");
        assertThat(ran).containsExactly("a");
        fail[0] = false;
        runner.run("f", steps, "2:a");
        assertThat(ran).containsExactly("a", "b", "c");
        assertThatThrownBy(() -> runner.run("f", steps, "9:z")).isInstanceOf(IllegalArgumentException.class);
    }

    private StepOperations ops() {
        Answer<Object> named = invocation -> {
            if (invocation.getMethod().getName().equals("afonCatalogs")) {
                return new health.emrys.afon.config.AfonCatalogs(properties);
            }
            if (invocation.getMethod().getReturnType() == Step.class) {
                return new Step(invocation.getMethod().getName(), "", () -> { });
            }
            return RETURNS_DEFAULTS.answer(invocation);
        };
        StepOperations ops = mock(StepOperations.class, named);
        org.mockito.Mockito.doReturn(properties).when(ops).properties();
        return ops;
    }
}
