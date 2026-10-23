package health.emrys.afon.ops.scripts;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackComponents;
import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.config.StackRole;
import health.emrys.afon.ops.plan.ComponentPlanRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a stack needs of the env it is deployed to, as the scripts that create it need to know, derived from the stack as the
 * stacks file defines it and the plans of its components, so that the scripts need not repeat it per stack: the properties of
 * the domain names whose DNS zones it needs, and the kinds of component it deploys, eg to know that it needs a bucket for
 * Gitea if it has Git.
 * <p>
 * The stack is given by its id, or by its role in the cloud of the script, see {@link #resolve}.
 *
 * @param id         the stack
 * @param role       its role, or {@code null} if it has none
 * @param domains    the properties of the domain names that its components hold, eg {@code AZURE_POLARIS_DOMAIN_NAME}, in order
 * @param components the names of the components it deploys for the config, in order, eg {@code Polaris}
 */
public record StackNeeds(StackId id, StackRole role, List<String> domains, List<String> components) {

    /**
     * @param cloud      the cloud of the script
     * @param stack      a stack id, eg {@code azure-full}, or the role of a stack of the cloud, eg {@code tre}, or the role as
     *                   {@code <cloud>-<role>}, eg {@code azure-tre}, as the commands that take a stack and no cloud accept it
     * @param properties the config, as which components a stack deploys depends on it, eg whether it deploys its own KeyCloak
     * @throws IllegalArgumentException if it is not a stack of the cloud that the stacks file defines, naming the file
     */
    public static StackNeeds resolve(AfonCloud cloud, String stack, AfonPropertyProvider properties) {
        StackDefinition definition = StackRole.of(stack.trim().toLowerCase())
            .map(role -> StackDefinitions.withRole(cloud, role).orElseThrow(() -> new IllegalArgumentException(String.format(
                "No stack of %s has the role '%s' in the stacks of %s", cloud, role, StackDefinitions.source()))))
            .orElseGet(() -> byId(cloud, stack));
        ComponentPlanRegistry plans = ComponentPlanRegistry.standard();
        Set<String> domains = new LinkedHashSet<>();
        List<String> components = new ArrayList<>();
        for (StackComponent component : definition.componentsFor(properties)) {
            components.add(StackComponents.nameOf(component).orElse(component.getClass().getSimpleName()));
            plans.of(component).domains(cloud, properties).stream().map(AfonProperty::getName).forEach(domains::add);
        }
        return new StackNeeds(definition.id(), definition.role(), List.copyOf(domains), List.copyOf(components));
    }

    /**
     * What a script is given about its stack, in env vars: what the stack needs, see {@link #env()}, if a stack is given, else
     * the stacks that can serve the component, as {@code AFON_STACK_CANDIDATES}, the ids separated by spaces.
     *
     * @param stack     a stack id or role, see {@link #resolve}, or {@code null} if none is given
     * @param component the component that the script checks the stack serving, or {@code null} if it serves none
     * @return the env vars, none if there is no stack nor component
     */
    public static Map<String, String> env(AfonCloud cloud, String stack, String component, AfonPropertyProvider properties) {
        if (stack != null) {
            return resolve(cloud, stack, properties).env();
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (component != null) {
            env.put("AFON_STACK_CANDIDATES", serving(cloud, component, properties).stream().map(Object::toString)
                                                                              .collect(java.util.stream.Collectors.joining(" ")));
        }
        return env;
    }

    /**
     * @param component the name of a kind of component, eg {@code Polaris}
     * @return the stacks of the cloud that deploy it for the config, in order of their ids, ie those that can serve it, eg to
     * find which of them is running: as they hold what the component holds, eg its domain name, one of them at a time
     * @throws IllegalArgumentException if there is no such component
     */
    public static List<StackId> serving(AfonCloud cloud, String component, AfonPropertyProvider properties) {
        Class<? extends StackComponent> type = StackComponents.find(component).orElseThrow(() -> new IllegalArgumentException(
            String.format("Unknown component '%s', expected one of %s", component, StackComponents.names()))).get().getClass();
        return StackDefinitions.all().stream()
                               .filter(definition -> definition.id().cloud() == cloud)
                               // not checked for being composed correctly, as another stack's mistake is not this one's
                               .filter(definition -> definition.components().apply(properties).stream()
                                                               .anyMatch(deployed -> deployed.getClass().equals(type)))
                               .map(StackDefinition::id).toList();
    }

    private static StackDefinition byId(AfonCloud cloud, String stack) {
        StackId id = StackDefinitions.resolve(StackId.parse(stack));
        if (id.cloud() != cloud) {
            throw new IllegalArgumentException(String.format("Stack %s is not a stack of %s", id, cloud));
        }
        return StackDefinitions.find(id).orElseThrow(() -> new IllegalArgumentException(String.format(
            "No stack '%s' in the stacks of %s", id, StackDefinitions.source())));
    }

    /**
     * @return the environment variables that give a script what the stack needs: {@code AFON_STACK_ID},
     * {@code AFON_STACK_ROLE} (empty if it has none), {@code AFON_STACK_DOMAINS} and {@code AFON_STACK_COMPONENTS}, with the
     * lists separated by spaces
     */
    public Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AFON_STACK_ID", id.toString());
        env.put("AFON_STACK_ROLE", Optional.ofNullable(role).map(StackRole::value).orElse(""));
        env.put("AFON_STACK_DOMAINS", String.join(" ", domains));
        env.put("AFON_STACK_COMPONENTS", String.join(" ", components));
        return env;
    }
}
