package health.emrys.afon.ops.steps;

import org.jspecify.annotations.NonNull;

import java.util.function.Predicate;

/**
 * Selects the stacks of a cloud by name, eg {@code aws-keycloak}, or the cloud, eg {@code aws}, for the legacy stack,
 * whose dir and tofu state were shared by all the stacks of the cloud.
 *
 * @param description what the selected stacks are, eg "conflicting with aws-polaris", to describe the selection
 * @param selects     whether a stack is selected, given its name
 */
public record StackFilter(@NonNull String description, @NonNull Predicate<String> selects) {

    /**
     * Selects every stack.
     */
    public static final StackFilter ALL = new StackFilter("every stack", name -> true);

    @Override
    public String toString() {
        return description;
    }

}
