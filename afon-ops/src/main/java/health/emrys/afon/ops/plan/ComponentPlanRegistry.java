package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.components.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of {@link ComponentPlan} for each kind of {@link StackComponent}, ie what each brings to the commands on a stack,
 * besides being deployed with it.  Each component must declare it, even if it brings nothing, see {@link InfrastructurePlan}:
 * a test requires the registry to be complete, and a component without one fails the commands with a message saying so.
 */
public final class ComponentPlanRegistry {

    public static final ComponentPlan NO_OP = new ComponentPlan() {};
    private final Map<Class<? extends StackComponent>, ComponentPlan> plans = new HashMap<>();

    public static ComponentPlanRegistry standard() {
        ComponentPlanRegistry registry = new ComponentPlanRegistry();

        registry.add(KeycloakComponent.class, new KeycloakPlan());
        registry.add(PolarisComponent.class, new PolarisPlan());
        registry.add(JupyterComponent.class, new JupyterPlan());
        registry.add(SparkComponent.class, new SparkPlan());
        registry.add(SnowflakeComponent.class, new SnowflakePlan());
        registry.add(LoggingComponent.class, NO_OP);
        registry.add(GitComponent.class, NO_OP);

        // infrastructure with nothing to wait for or configure
        for (Class<? extends StackComponent> type : List.of(
            NetworkPoliciesComponent.class, SparkOperatorComponent.class, TrinoComponent.class,
            CrdCleanupComponent.class, MonitoringComponent.class, TetragonComponent.class,
            KyvernoComponent.class)) {
            registry.add(type, new InfrastructurePlan());
        }
        return registry;
    }

    public void add(Class<? extends StackComponent> type, ComponentPlan componentPlan) {
        plans.put(type, componentPlan);
    }

    /**
     * @throws IllegalStateException if none is declared for the component, as the stack cannot be managed without knowing
     */
    public ComponentPlan of(StackComponent component) {
        ComponentPlan componentPlan = plans.get(component.getClass());
        if (componentPlan == null) {
            throw new IllegalStateException(String.format(
                "No manage-stack plan declared for %s: declare them in %s, even if there are none",
                component.getClass().getSimpleName(), ComponentPlanRegistry.class.getSimpleName()));
        }
        return componentPlan;
    }
}
