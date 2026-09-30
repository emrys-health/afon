package health.emrys.afon.ops.steps;

import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Selects the stacks of a cloud by name, eg {@code aws-keycloak}, or the cloud, eg {@code aws}, for the legacy stack,
 * whose dir and tofu state were shared by all the stacks of the cloud.
 *
 * @param description what the selected stacks are, eg "stacks conflicting with aws-polaris", to describe the selection
 * @param selects     whether a stack is selected, given its name
 * @param refusal     why a selected stack, given its name, must not be deleted without {@code --replace}, and how to
 *                    delete it instead, or {@code null} if it may be
 * @param deploying   the stack to deploy, for which the stacks are deleted, if any, eg {@code aws-polaris}
 */
public record StackFilter(@NonNull String description, @NonNull Predicate<String> selects,
                          @NonNull Function<String, Refusal> refusal, StackId deploying) {

    /**
     * Why a stack must not be deleted without {@code --replace}.
     *
     * @param reason        eg "stack aws-full is running and already deploys Polaris at ..."
     * @param deleteCommand the command deleting the stack instead, eg {@code afon-cli.sh manage-stack delete aws-full},
     *                      or {@code null} if only {@code --replace} can
     */
    public record Refusal(@NonNull String reason, String deleteCommand) {
    }

    /**
     * Selects every stack.
     */
    public static final StackFilter ALL = new StackFilter("every stack", name -> true);

    /**
     * A filter whose selected stacks may all be deleted.
     */
    public StackFilter(@NonNull String description, @NonNull Predicate<String> selects) {
        this(description, selects, name -> null, null);
    }

    @Override
    public String toString() {
        return description;
    }

}
