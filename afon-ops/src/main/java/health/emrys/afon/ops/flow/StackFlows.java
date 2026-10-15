package health.emrys.afon.ops.flow;

import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.flow.StackFlowsFile.ItemSpec;
import health.emrys.afon.ops.flow.StackFlowsFile.StackRef;
import health.emrys.afon.ops.plan.StackPlanFactory;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static health.emrys.afon.config.ConfigUtils.configDir;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The flows that {@code manage-stack run} runs, defined in {@code stack-flows.yaml}, see {@link StackFlowsFile}, and validated
 * when used, see {@link StackFlow#validate}.
 * <p>
 * A flow is of the stacks that have a {@link health.emrys.afon.config.StackRole} in their cloud, or deploy a kind of component,
 * rather than of stacks by id, so that the stacks file may name them as it likes.  A flow whose stacks the stacks file does not
 * define cannot run: it is not among {@link #all()}, which lists those that can, and {@link #get} fails for it, naming
 * the stack.
 */
public final class StackFlows {

    private static final Logger logger = getLogger(StackFlows.class);

    private static Map<String, List<ItemSpec>> loaded;
    private static Path loadedFrom;

    /**
     * Thrown when finding the stack of an item, if the stacks file does not define it.
     */
    private static final class NoSuchStack extends RuntimeException {
        NoSuchStack(String description) {
            super(description, null, false, false);
        }
    }

    private StackFlows() {
    }

    /**
     * @return the flows as defined, loaded when first used, and again if the file to load has changed since
     */
    private static synchronized Map<String, List<ItemSpec>> definitions() {
        Path file = StackFlowsFile.file(System.getProperty(StackFlowsFile.PROPERTY), configDir());
        if (loaded == null || !Objects.equals(file, loadedFrom)) {
            loaded = StackFlowsFile.load(file);
            loadedFrom = file;
        }
        return loaded;
    }

    /**
     * Loads the flows if not yet loaded, for the commands to fail before doing anything if they are not valid.  The message
     * names the file and the problem.
     *
     * @throws IllegalStateException if the flows are not valid
     */
    public static void checkLoads() {
        definitions();
    }

    /**
     * Writes the bundled {@code stack-flows.yaml} to the config dir, for the user to edit, which then replaces the bundled one.
     *
     * @param configDir the dir to write it to, created if missing
     * @return the file written
     * @throws IllegalStateException if there is a file already, which is never overwritten, or it cannot be written
     */
    public static Path exportBundled(Path configDir) {
        return StackFlowsFile.exportBundled(configDir);
    }

    private static StackId find(StackRef ref) {
        if (ref.id() != null) {
            return StackDefinitions.find(ref.id()).map(StackDefinition::id)
                                   .orElseThrow(() -> new NoSuchStack("the stack '" + ref.id() + "'"));
        }
        if (ref.role() != null) {
            return StackDefinitions.withRole(ref.cloud(), ref.role()).map(StackDefinition::id)
                                   .orElseThrow(() -> new NoSuchStack(String.format("the %s stack of %s", ref.role(), ref.cloud())));
        }
        return StackDefinitions.declaring(ref.cloud(), ref.component()).map(StackDefinition::id).orElseThrow(
            () -> new NoSuchStack(String.format("the stack of %s with %s", ref.cloud(), ref.component().getSimpleName())));
    }

    private static boolean isStackId(String name) {
        try {
            StackId id = StackId.parse(name);
            return id.isAll() || StackDefinitions.find(id).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @return the flow with the stacks of the stacks file in its items
     * @throws NoSuchStack if the stacks file does not define a stack that an item needs
     */
    private static StackFlow resolve(String name, List<ItemSpec> specs) {
        return new StackFlow(name, specs.stream()
            .map(spec -> new FlowItem(spec.action(), find(spec.stack()), spec.scenario())).toList());
    }

    /**
     * @return the flows whose stacks the stacks file defines, in order
     * @throws IllegalStateException if a flow is named as a stack id, as {@code run <name>} runs a flow if it names one, or the
     *                               flows are not valid
     */
    public static Collection<StackFlow> all() {
        List<StackFlow> flows = new ArrayList<>();
        definitions().forEach((name, specs) -> {
            if (isStackId(name)) {
                throw new IllegalStateException("Flow '" + name + "' is named as a stack id");
            }
            try {
                flows.add(resolve(name, specs));
            } catch (NoSuchStack e) {
                logger.debug("Flow '{}' cannot run, as {} is not defined in the stacks of {}", name, e.getMessage(),
                             StackDefinitions.source());
            }
        });
        return flows;
    }

    /**
     * @return whether the flow is defined, whether or not the stacks file defines the stacks it needs, so that running it fails
     * saying so, rather than being taken for a stack id
     */
    public static boolean isDefined(String name) {
        return definitions().containsKey(name);
    }

    public static Optional<StackFlow> find(String name) {
        return all().stream().filter(flow -> flow.name().equals(name)).findFirst();
    }

    /**
     * @return the flow, validated
     * @throws IllegalArgumentException if there is no such flow, or it needs a stack that the stacks file does not define, or
     *                                  it is not valid
     */
    public static StackFlow get(String name, StackPlanFactory factory, AfonPropertyProvider properties) {
        List<ItemSpec> specs = definitions().get(name);
        if (specs == null) {
            throw new IllegalArgumentException("No such flow '" + name + "'");
        }
        StackFlow flow;
        try {
            flow = resolve(name, specs);
        } catch (NoSuchStack e) {
            throw new IllegalArgumentException(String.format("Flow '%s' cannot run, as %s is not defined in the stacks of %s",
                                                             name, e.getMessage(), StackDefinitions.source()));
        }
        flow.validate(factory, properties);
        return flow;
    }
}
