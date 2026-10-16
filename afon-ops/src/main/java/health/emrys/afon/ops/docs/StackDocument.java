package health.emrys.afon.ops.docs;

import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.GCP_PROJECT_ID;
import static java.lang.String.format;

/**
 * The document of an action on a stack, run with {@code manage-stack <action> <stack-id>}, whose steps are those it runs,
 * provided by {@link StackPlanFactory}, eg {@code setup <stack-id>}, or {@code delete <cloud>-all}, which deletes every stack
 * of the cloud.
 */
public class StackDocument extends AbstractDocument {

    private static final StackPlanFactory FACTORY = new StackPlanFactory();

    private final StackAction action;
    private final StackId stackId;
    private final StepsProvider stepsProvider;

    public StackDocument(AfonPropertyProvider properties, StackAction action, StackId stackId) {
        super(properties);
        this.action = action;
        this.stackId = stackId;
        this.stepsProvider = FACTORY.stepsProviderFor(action, stackId, false);
        if (stepsProvider == null) {
            throw new IllegalArgumentException(format("Unsupported action '%s' on stack '%s'", action, stackId));
        }
    }

    @Override
    public String getFilename() {
        return DocumentNames.stack(action, stackId);
    }

    @Override
    public Set<AfonCloud> clouds() {
        return Set.of(stackId.cloud());
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return stepsProvider.stepsUsing(stepOperations);
    }

    @Override
    protected String getSupplementaryIntro() {
        if (stackId.isAll()) {
            return format("Deletes every stack of the %s cloud.", cloudName());
        }
        String stack = format("the stack `%s`", stackId);
        return switch (action) {
            case DELETE -> format("Deletes %s.", stack);
            case DEPLOY -> format("Deploys %s.", stack);
            default -> format("Sets up %s%s.", stack, populatedWith());
        };
    }

    private String cloudName() {
        return stackId.cloud() == AfonCloud.AWS || stackId.cloud() == AfonCloud.GCP
            ? stackId.cloud().name() : "Azure";
    }

    private String populatedWith() {
        // by what the stack deploys, not by its id: the catalogs are in the Polaris, the realm in the KeyCloak
        StackDefinition definition = StackDefinitions.get(stackId);
        if (definition.declares(PolarisComponent.class)) {
            return ", populated with the test catalogs";
        }
        return definition.declares(KeycloakComponent.class) ? ", populated with the realm, clients and users" : "";
    }

    @Override
    protected String howToRun() {
        return format(
                """

                To execute, run:
                ```shell
                afon-cli.sh manage-stack %s
                ```
                %s""", manageStackArgs(), note());
    }

    private String manageStackArgs() {
        return action + " " + stackId;
    }

    private String note() {
        if (action == StackAction.DELETE) {
            if (!stackId.isAll()) {
                return "";
            }
            List<String> stackIds = FACTORY.supportedActions().keySet().stream()
                                           .filter(id -> id.cloud() == stackId.cloud() && !id.isAll())
                                           .map(StackId::toString)
                                           .toList();
            return format(
                """

                To delete just one stack of cloud `%s`, give its stack id instead, ie one of %s.
                """, stackId.cloud(), stackIds.stream().map(id -> "`" + id + "`").collect(Collectors.joining(", ")));
        }
        return format(
            """

            If another running stack conflicts with `%s`, it fails before deleting any stack: add `--replace` to
            replace the conflicting stacks too.
            """, stackId);
    }

    @Override
    protected String howToResume(String lastStep) {
        return format(
                """
                If the job fails on a given step, you don't need to rerun the full list of steps.
                Instead, you can add to the manage-stack args the step from which to restart:

                `afon-cli.sh manage-stack %s <one-of-the-steps-from-list-above>`

                This will continue the execution starting at the chosen step.

                For example:
                ```shell
                afon-cli.sh manage-stack %s %s
                ```
                """,
                manageStackArgs(),
                manageStackArgs(),
                lastStep);
    }

    @Override
    public String getFurtherNotes() {
        String stack = stackId.isAll() ? stackId.cloud() + "-<name>" : stackId.toString();
        String repeat = stackId.cloud() == AfonCloud.AWS ? " (more than once if required)" : "";
        return format(
            """
            # Troubleshooting
            %s
            ### Problems destroying the stack?

            Generate and run cleanup script%s:
            ```shell
            afon-cli.sh generate-cleanup-script %s
            ```
            """, specificTroubleshooting(), repeat, stack);
    }

    /**
     * @return the troubleshooting specific to the stack, to precede the generic one
     */
    private String specificTroubleshooting() {
        if (stackId.isAll()) {
            return "";
        }
        // GCP's, as the troubleshooting is of the cloud, whichever its stacks
        if (stackId.cloud() != AfonCloud.GCP) {
            return "";
        }
        return format(
                """

                ### Problems running `tofu destroy`?
                If you see:
                ```
                │ Error: Error creating Network: googleapi: Error 409: The resource 'projects/%1$s/global/networks/gke-private-vpc' already exists, alreadyExists
                │   with google_compute_network.XXX,
                ```
                then import it with:
                ```shell
                tofu import google_compute_network.XXX projects/%1$s/global/networks/gke-private-vpc
                ```
                where XXX is bespoke to you.
                You might need to delete offending providers.

                If you get:
                ```
                │ Error: Error waiting for Deleting Network: The subnetwork resource 'projects/%1$s/regions/europe-west2/subnetworks/gke-private-subnet' is already being used by 'projects/%1$s/zones/europe-west2-a/instanceGroups/gke-spark-cluster-default-pool-XXX'
                ```
                then, for that XXX, run:
                ```shell
                gcloud compute instance-groups managed delete gke-spark-cluster-default-pool-XXX --zone europe-west2-a --project %1$s --quiet
                ```
                """, gcpProjectId());
    }

    /**
     * @return the configured {@code GCP_PROJECT_ID}, or its name if not set, as in the templates
     */
    private String gcpProjectId() {
        String projectId = properties.getValue(GCP_PROJECT_ID, "").trim();
        return projectId.isEmpty() ? "<GCP_PROJECT_ID>" : projectId;
    }

}
