package health.emrys.afon.ops.flow;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static health.emrys.afon.ops.flow.FlowItem.run;
import static health.emrys.afon.ops.flow.FlowItem.setup;

/**
 * The flows that {@code manage-stack run} runs, defined in code as {@link health.emrys.afon.cdk.StackDefinitions} defines
 * the stacks, and validated when loaded, see {@link StackFlow#validate}.
 */
public final class StackFlows {

    private static final Map<String, StackFlow> FLOWS = new LinkedHashMap<>();

    static {
        add(StackFlow.of("aws-full-and-azure-polaris",
                setup("aws-full"),
                setup("azure-polaris")));
        add(StackFlow.of("aws-keycloak-and-polaris",
                setup("aws-keycloak"),
                setup("aws-polaris")));
        add(StackFlow.of("azure-and-gcp-polaris",
                setup("azure-polaris"),
                setup("gcp-polaris")));
        add(StackFlow.of("cross-cloud-azure",
                 setup("aws-full"),
                 setup("azure-polaris"),
                 run("aws-full", "external-azure")));
        for (String scenario : new String[]{"reading-aws", "reading-azure", "reading-gcp", "writing-azure"}) {
            add(StackFlow.of("snowflake-" + scenario,
                 setup("aws-snowflake"),
                 run("aws-snowflake", scenario)));
        }
        // just the runs, on the aws-snowflake stack, which must be set up
        add(StackFlow.of("snowflake-reading-all",
                 run("aws-snowflake", "reading-aws"),
                 run("aws-snowflake", "reading-azure"),
                 run("aws-snowflake", "reading-gcp")));
    }

    private StackFlows() {
    }

    private static void add(StackFlow flow) {
        // run <name> is a flow if it names one, else the stack of that id
        if (isStackId(flow.name())) {
            throw new IllegalStateException("Flow '" + flow.name() + "' is named as a stack id");
        }
        FLOWS.put(flow.name(), flow);
    }

    private static boolean isStackId(String name) {
        try {
            StackId id = StackId.parse(name);
            return id.isAll() || StackDefinitions.find(id).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Collection<StackFlow> all() {
        return FLOWS.values();
    }

    public static Optional<StackFlow> find(String name) {
        return Optional.ofNullable(FLOWS.get(name));
    }

    /**
     * @return the flow, validated
     * @throws IllegalArgumentException if there is no such flow, or it is not valid
     */
    public static StackFlow get(String name, StackPlanFactory factory, AfonPropertyProvider properties) {
        StackFlow flow = find(name).orElseThrow(() -> new IllegalArgumentException("No such flow '" + name + "'"));
        flow.validate(factory, properties);
        return flow;
    }
}
