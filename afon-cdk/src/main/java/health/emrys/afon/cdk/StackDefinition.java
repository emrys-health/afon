package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.config.StackRole;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What a stack is composed of: the components deployed to its cloud, in order, as each may depend on those before it.
 *
 * @param id             the stack, whose cloud it is deployed to
 * @param role           the {@link StackRole} it plays in its cloud, or {@code null} if none
 * @param pinnedEgressIp for AWS, whether the stack's egress IP is pinned to {@code AWS_EGRESS_IP}, if set, which only
 *                       one running stack can hold, else the stack allocates its own
 * @param components     creates the components, new for each stack built as they hold what they deploy
 * @param declared       the kinds of component it may deploy, ie that its definition lists, whatever the config
 */
public record StackDefinition(StackId id,
                              @Nullable StackRole role,
                              boolean pinnedEgressIp,
                              Function<AfonPropertyProvider, List<StackComponent>> components,
                              Set<Class<? extends StackComponent>> declared) {

    /**
     * A stack with no role, that declares nothing.
     */
    public StackDefinition(StackId id, boolean pinnedEgressIp,
                           Function<AfonPropertyProvider, List<StackComponent>> components) {
        this(id, null, pinnedEgressIp, components, Set.of());
    }

    /**
     * @return whether the stack may deploy the kind of component, ie its definition lists it, although the config may leave it out
     */
    public boolean declares(Class<? extends StackComponent> type) {
        return declared.contains(type);
    }

    /**
     * @return the stack's components for the config, new for each call, checked to be composed correctly
     * @throws IllegalStateException if a component is more than once in the stack, or the stack has not what a component
     *                               requires before it, or has what it must come after, after it
     */
    public List<StackComponent> componentsFor(AfonPropertyProvider properties) {
        List<StackComponent> components = components().apply(properties);
        validate(id, components);
        return components;
    }

    static void validate(StackId id, List<StackComponent> components) {
        Set<Class<? extends StackComponent>> deployed = new HashSet<>();
        for (StackComponent component : components) {
            String name = component.getClass().getSimpleName();
            if (deployed.contains(component.getClass())) {
                throw new IllegalStateException(String.format("Stack %s has %s more than once", id, name));
            }
            for (Class<? extends StackComponent> required : component.requires()) {
                if (!deployed.contains(required)) {
                    throw new IllegalStateException(String.format(
                        "Stack %s needs %s before %s", id, required.getSimpleName(), name));
                }
            }
            deployed.add(component.getClass());
        }
        // what a component must come after, if the stack has it, must not be deployed later
        Set<Class<? extends StackComponent>> seen = new HashSet<>();
        for (StackComponent component : components) {
            seen.add(component.getClass());
            for (Class<? extends StackComponent> before : component.after()) {
                boolean hasIt = components.stream().anyMatch(other -> other.getClass().equals(before));
                if (hasIt && !seen.contains(before)) {
                    throw new IllegalStateException(String.format(
                        "Stack %s needs %s before %s", id, before.getSimpleName(), component.getClass().getSimpleName()));
                }
            }
        }
    }
}
