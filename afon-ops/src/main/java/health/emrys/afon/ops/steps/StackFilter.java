package health.emrys.afon.ops.steps;

import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Selects the stacks of a cloud by name, ie of the stack id, or the cloud, eg {@code aws}, for the legacy stack,
 * whose dir and tofu state were shared by all the stacks of the cloud.
 * <p>
 * A stack <em>name</em> is that of a stack dir or tofu state in the cdktf dir, as found by {@code StepActions}.  Unlike a
 * {@link StackId}, which always identifies a stack the stacks file defines, a name may also be:
 * <ul>
 *     <li>that of the cloud, for the legacy stack, which may be any stack, so conflicts with every one;</li>
 *     <li>the text of a stack id that the stacks file does not define now, eg left by an earlier build or file, whose
 *         resources are unknown, so it conflicts with every stack as well.</li>
 * </ul>
 * Hence the predicates take the name, and parse it into a {@link StackId} only if it is neither.
 *
 * @param description what the selected stacks are, eg "stacks conflicting with <stack-id>", to describe the selection
 * @param selects     whether a stack is selected, given its name
 * @param refusal     why a selected stack, given its name, must not be deleted without {@code --replace}, and how to
 *                    delete it instead, or {@code null} if it may be
 * @param deploying   the stack to deploy, for which the stacks are deleted, if any
 */
public record StackFilter(@NonNull String description, @NonNull Predicate<String> selects,
                          @NonNull Function<String, Refusal> refusal, StackId deploying) {

    /**
     * Why a stack must not be deleted without {@code --replace}.
     *
     * @param reason        eg "stack <stack-id> is running and already deploys Polaris at ..."
     * @param deleteCommand the command deleting the stack instead, eg {@code afon-cli.sh manage-stack delete <stack-id>},
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
