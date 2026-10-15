package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * The kinds of {@link StackComponent} that the stacks can be composed of, by the names with which the stacks file refers to
 * them, ie the class names without {@code Component}, eg {@code Polaris}.  A name creates a new component each time, as
 * a component holds what it deploys.
 * <p>
 * This is the only place that maps the names of the stacks file to the components of the code: {@link StackDefinitions} asks
 * it for the component of each name in the file.
 */
public final class StackComponents {

    private static final Map<String, Supplier<StackComponent>> COMPONENTS = new TreeMap<>();

    static {
        COMPONENTS.put("Keycloak", KeycloakComponent::new);
        COMPONENTS.put("Polaris", PolarisComponent::new);
        COMPONENTS.put("Jupyter", JupyterComponent::new);
        COMPONENTS.put("NetworkPolicies", NetworkPoliciesComponent::new);
        COMPONENTS.put("SparkOperator", SparkOperatorComponent::new);
        COMPONENTS.put("Spark", SparkComponent::new);
        COMPONENTS.put("CrdCleanup", CrdCleanupComponent::new);
        COMPONENTS.put("Trino", TrinoComponent::new);
        COMPONENTS.put("Monitoring", MonitoringComponent::new);
        COMPONENTS.put("Logging", LoggingComponent::new);
        COMPONENTS.put("Git", GitComponent::new);
        COMPONENTS.put("Snowflake", SnowflakeComponent::new);
    }

    private StackComponents() {
    }

    /**
     * @return the names of the components, in order
     */
    public static Set<String> names() {
        return Collections.unmodifiableSet(COMPONENTS.keySet());
    }

    /**
     * @return what creates the component with the name, or empty if there is none
     */
    public static Optional<Supplier<StackComponent>> find(String name) {
        return Optional.ofNullable(COMPONENTS.get(name));
    }
}
