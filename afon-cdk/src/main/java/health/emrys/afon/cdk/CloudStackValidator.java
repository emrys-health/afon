package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.StackId;
import org.slf4j.Logger;

import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Validates the config of a stack by synthesising its cdktf plan into {@code <afon.cdktf>/stacks/<stack-id>},
 * without deploying it, eg to check that the config dir has all the properties the stack needs.
 * It overwrites the synthesised plan of any previous deploy of the stack in that dir, but not the tofu state file
 * {@code <afon.cdktf>/terraform.<stack-id>.tfstate}, as it doesn't run tofu.
 * <p>
 * The stacks are those of {@link StackDefinitions}.
 */
public class CloudStackValidator {

    private static final Logger logger = getLogger(CloudStackValidator.class);

    /**
     * @return the stacks, in order of their ids
     */
    public List<StackId> stacks() {
        return StackDefinitions.all().stream().map(StackDefinition::id).toList();
    }

    /**
     * @return the stack names, in kebab case, ie their ids, {@code <cloud>-<stack-type>}
     */
    public List<String> stackNames() {
        return stacks().stream().map(StackId::toString).toList();
    }

    /**
     * Resolves a stack id, case-insensitively, eg "<cloud>-<stack-type>" or "<CLOUD>-<STACK-TYPE>", or the role of a stack in its
     * cloud, as "<cloud>-<role>", see {@link StackDefinitions#resolve}.
     *
     * @return the stack, or {@code null} if there is no such stack
     */
    public StackId resolve(String name) {
        try {
            StackId stackId = StackDefinitions.resolve(StackId.parse(name));
            return StackDefinitions.find(stackId).isPresent() ? stackId : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the dir the stack is synthesised into, ie {@code <afon.cdktf>/stacks/<stack-id>}.
     */
    public String stackDir(StackId stackId) {
        return ConfigUtils.stackDir(stackId);
    }

    /**
     * Validates the stack by synthesising its plan into {@link #stackDir(StackId)}, from the given config,
     * which is that of the caller, eg the operations of the steps, rather than one read again, and the users.
     *
     * @return the dir the stack was synthesised into
     * @throws RuntimeException if the stack fails to synthesise, eg because a property is missing
     */
    public String validate(StackId stackId, AfonPropertyProvider properties, AfonUserProvider users) {
        String stackDir = stackDir(stackId);
        logger.info("Validating stack {} by synthesising it into {}", stackId, stackDir);
        try {
            ComposedStack.synthesise(stackId, properties, users);
        } catch (RuntimeException e) {
            logger.error("Failed to validate stack {}", stackId, e);
            throw e;
        }
        logger.info("Validated stack {} by synthesising it into {}", stackId, stackDir);
        return stackDir;
    }

}
