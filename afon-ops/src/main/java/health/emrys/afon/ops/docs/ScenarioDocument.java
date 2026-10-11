package health.emrys.afon.ops.docs;

import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.flow.FlowItem;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;

/**
 * The document of a scenario of a stack, run with {@code manage-stack run <stack-id> <scenario>} on the stack once it is
 * set up, as well as the other stacks it needs, if any, whose steps are provided by {@link StackPlanFactory}.
 */
public class ScenarioDocument extends AbstractDocument {

    private final StackPlanFactory factory = new StackPlanFactory();
    private final StackId stack;
    private final List<StackId> setups;
    private final String scenario;

    /**
     * @param stackId  the stack the scenario is run on, eg {@code aws-full}
     * @param scenario the scenario of the stack, eg {@code external-azure}
     */
    public ScenarioDocument(AfonPropertyProvider properties, StackId stackId, String scenario) {
        super(properties);
        this.stack = stackId;
        this.scenario = scenario;
        this.setups = setupsOf(stackId, scenario);
    }

    /**
     * @return the stacks to set up first, in order: those that the flow ending with the scenario sets up, eg {@code aws-full}
     * and {@code azure-polaris} for {@code external-azure}, which needs the Azure Polaris too, else just the stack
     */
    private static List<StackId> setupsOf(StackId stack, String scenario) {
        return StackFlows.all().stream()
                         .filter(flow -> flow.items().get(flow.items().size() - 1).equals(FlowItem.run(stack.toString(), scenario)))
                         .map(flow -> flow.items().stream().filter(item -> item.action() == StackAction.SETUP)
                                          .map(FlowItem::stackId).toList())
                         .filter(setups -> !setups.isEmpty())
                         .findFirst().orElse(List.of(stack));
    }

    @Override
    public String getFilename() {
        return DocumentNames.scenario(stack, scenario);
    }

    @Override
    public Set<AfonCloud> clouds() {
        return Stream.concat(Stream.of(stack), setups.stream()).map(StackId::cloud).collect(Collectors.toSet());
    }

    @Override
    protected String getSupplementaryIntro() {
        if (stack.toString().equals("aws-snowflake") && scenario.startsWith("reading-")) {
            return format(
                """
                ### Summary of this deployment

                This deployment uses Spark running locally but talking to the AWS Spark Connector to create an
                Iceberg table in %s. This table is automatically registered in the AWS Polaris as part of
                the creation process.

                We then point Snowflake at this Polaris running in our real estate and run a SF query against it.
                """, scenario.substring("reading-".length()).toUpperCase());
        }
        return switch (stack + " " + scenario) {
            case "aws-snowflake writing-azure" ->
                """
                ## What it does
                This e2e test has Snowflake writing to Azure blob storage, registering its
                metadata with Polaris then running Spark against Polaris to see the same data.

                """;
            case "aws-full external-azure" ->
                """
                Spark, running locally, writes through the AWS Polaris to an external catalog hosted by the Azure Polaris:
                - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
                - a smoke test that ensures that Spark can talk to the AWS catalog that is a proxy for the Azure one
                """;
            default -> format("Runs the scenario `%s` on the %s stack.", scenario, stack);
        };
    }

    @Override
    public String getFurtherNotes() {
        if (setups.stream().noneMatch(setup -> setup.cloud() == AfonCloud.AZURE)) {
            return "";
        }
        return String.format("""
            # Troubleshooting

            ### Problems destroying the Azure stack?
            Run:
            ```shell
            %s
            ```

            If all else fails, destroy the Kubernetes cluster directly in the
            [Azure console](https://portal.azure.com/#view/Microsoft_Azure_KubernetesFleet/KubernetesHub.MenuView/~/clusters)
            """, new AzureCleanupHelper(properties).cleanupCommands());
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return factory.scenarioSteps(stack, scenario, stepOperations);
    }

    @Override
    protected String howToRun() {
        String setupCommands = setups.stream().map(id -> "afon-cli.sh manage-stack setup " + id).collect(Collectors.joining("\n"));
        return format(
                """

                To execute, first set up the %s, unless already set up:
                ```shell
                %s
                ```
                If another running stack conflicts with %s, it fails before deleting any stack: add `--replace` to
                replace the conflicting stacks too.

                Then run the scenario, which is what the steps below are:
                ```shell
                afon-cli.sh manage-stack run %s %s
                ```
                """,
                setups.size() == 1 ? "stack" : "stacks, in this order", setupCommands,
                setups.size() == 1 ? "it" : "one of them", stack, scenario);
    }

    @Override
    protected String howToResume(String lastStep) {
        return format(
                """
                If the job fails on a given step, you don't need to rerun the full list of steps.
                Instead, you can add to the manage-stack args the step from which to restart:

                `afon-cli.sh manage-stack run %s %s <one-of-the-steps-from-list-above>`

                This will continue the execution starting at the chosen step.

                For example:
                ```shell
                afon-cli.sh manage-stack run %s %s %s
                ```
                """, stack, scenario, stack, scenario, lastStep);
    }
}
