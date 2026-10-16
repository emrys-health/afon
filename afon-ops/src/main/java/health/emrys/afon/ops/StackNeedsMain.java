package health.emrys.afon.ops;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.scripts.StackNeeds;

import java.util.List;
import java.util.Map;

/**
 * Prints what the shell scripts need to know about a stack, as {@code NAME=value} lines, for a script that was not run by
 * {@code ScriptMain}, which gives them in its env otherwise: that is, a script run by {@code afon.sh}, which sources the scripts
 * in bash with no JVM, or directly.  Usage: {@code stack-needs <cloud> <stack-id|role|none> [<component>]}.
 * <ul>
 *   <li>With a stack, given by id or role: {@code AFON_STACK_ID}, {@code AFON_STACK_ROLE}, {@code AFON_STACK_DOMAINS} and
 *       {@code AFON_STACK_COMPONENTS}, see {@link StackNeeds#env()}.</li>
 *   <li>With {@code none} and a component: {@code AFON_STACK_CANDIDATES}, the stacks that deploy the component.</li>
 * </ul>
 */
public class StackNeedsMain {

    /**
     * The value for the stack when none is given, as the component follows it.
     */
    static final String NONE = "none";

    /**
     * @return the lines to print
     * @throws IllegalArgumentException if the args are not as above, or the stack is not one of the cloud in the stacks file
     */
    static List<String> lines(String[] args, AfonPropertyProvider properties) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: stack-needs <cloud> <stack-id|role|" + NONE + "> [<component>]");
        }
        AfonCloud cloud;
        try {
            cloud = AfonCloud.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown cloud '" + args[0] + "', expected one of aws, azure or gcp");
        }
        String stack = NONE.equals(args[1]) ? null : args[1];
        String component = args.length == 3 ? args[2] : null;
        if (stack == null && component == null) {
            throw new IllegalArgumentException("Usage: stack-needs <cloud> <stack-id|role|" + NONE + "> [<component>]");
        }
        return StackNeeds.env(cloud, stack, component, properties).entrySet().stream()
                         .map(Map.Entry::toString).toList();
    }

    public static void main(String[] args) {
        try {
            StackDefinitions.checkLoads();
            lines(args, new OpsPropertyProvider()).forEach(System.out::println);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
