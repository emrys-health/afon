package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.CrdCleanupComponent;
import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.cdk.aws.AwsLoggingComponent;
import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.cdk.components.NetworkPoliciesComponent;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.cdk.components.SnowflakeComponent;
import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.cdk.components.SparkOperatorComponent;
import health.emrys.afon.cdk.components.TrinoComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;

/**
 * The stacks, each composed of {@link StackComponent}s that run on whatever cloud the stack is deployed to.  A stack
 * is its {@link StackId}, eg {@code aws-full}, and the list of its components, so a new stack, eg of a different
 * subset of components, or on another cloud, is a new entry here.
 */
public final class StackDefinitions {

    private static final Map<StackId, StackDefinition> DEFINITIONS = new TreeMap<>();

    static {
        // AWS
        // Only the KeyCloak, never holding the egress IP, which the Polaris stacks hold
        add(new StackId(AWS, "keycloak"), false, properties -> List.of(new KeycloakComponent()));
        // Polaris uses the KeyCloak server at OIDC_DOMAIN_NAME, which the aws-keycloak stack deploys, so that both can
        // run side by side, while the stacks extending it deploy their own KeyCloak, unless AWS_KEYCLOAK_EXTERNAL is set
        add(new StackId(AWS, "polaris"), true, properties -> List.of(new PolarisComponent()));
        add(new StackId(AWS, "jupyter"), true, properties -> jupyter(isKeyCloakExternal(properties)));
        add(new StackId(AWS, "spark"), true,
            properties -> concat(jupyter(isKeyCloakExternal(properties)), spark()));
        add(new StackId(AWS, "trino"), true,
            properties -> concat(jupyter(isKeyCloakExternal(properties)), spark(), List.of(new TrinoComponent())));
        add(new StackId(AWS, "full"), true, properties -> full(isKeyCloakExternal(properties)));
        // Snowflake queries the catalogs of the full stack through its Polaris, so it holds what the full stack holds
        add(new StackId(AWS, "snowflake"), true,
            properties -> concat(full(isKeyCloakExternal(properties)), List.of(new SnowflakeComponent())));

        // AZURE
        add(new StackId(AZURE, "polaris"), true, properties -> List.of(new PolarisComponent()));
        // The same as aws-full, always deploying its own KeyCloak, as there is no azure-keycloak stack
        add(new StackId(AZURE, "full"), true, properties -> full(false));

        // GCP
        add(new StackId(GCP, "polaris"), true, properties -> List.of(new PolarisComponent()));
    }

    private StackDefinitions() {
    }

    private static void add(StackId id, boolean pinnedEgressIp,
                            java.util.function.Function<AfonPropertyProvider, List<StackComponent>> components) {
        DEFINITIONS.put(id, new StackDefinition(id, pinnedEgressIp, components));
    }

    /**
     * Polaris with JupyterHub and Guacamole behind the oauth2-proxy MFA gate, and the Spark operator the notebooks'
     * Spark runs on, deploying its own KeyCloak, unless configured to use another stack's.
     */
    private static List<StackComponent> jupyter(boolean keyCloakExternal) {
        List<StackComponent> components = new ArrayList<>();
        if (!keyCloakExternal) {
            components.add(new KeycloakComponent());
        }
        components.addAll(List.of(
            new PolarisComponent(), new JupyterComponent(), new NetworkPoliciesComponent(),
            new SparkOperatorComponent()));
        return components;
    }

    private static List<StackComponent> full(boolean keyCloakExternal) {
        return concat(jupyter(keyCloakExternal), spark(), List.of(new TrinoComponent(), new MonitoringComponent()));
    }

    // The operator only launches Spark in response to the resources of the Spark component
    private static List<StackComponent> spark() {
        return List.of(new SparkComponent(), new CrdCleanupComponent());
    }

    @SafeVarargs
    private static List<StackComponent> concat(List<StackComponent>... lists) {
        List<StackComponent> all = new ArrayList<>();
        for (List<StackComponent> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * @return whether the stacks otherwise deploying their own KeyCloak, eg {@code aws-full}, use instead the one at
     * {@code OIDC_DOMAIN_NAME} that the {@code aws-keycloak} stack deploys, as configured by
     * {@code AWS_KEYCLOAK_EXTERNAL}, {@code false} when unset
     */
    public static boolean isKeyCloakExternal(AfonPropertyProvider properties) {
        return Boolean.parseBoolean(properties.getValue(AWS_KEYCLOAK_EXTERNAL, "false").trim());
    }

    /**
     * @return the definition of the stack, or empty if there is none
     */
    public static Optional<StackDefinition> find(StackId id) {
        return Optional.ofNullable(DEFINITIONS.get(id));
    }

    /**
     * @throws IllegalArgumentException if there is no such stack
     */
    public static StackDefinition get(StackId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("No such stack: " + id));
    }

    /**
     * @return the stacks, in order of their ids
     */
    public static Collection<StackDefinition> all() {
        return DEFINITIONS.values();
    }
}
