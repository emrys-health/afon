package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.slf4j.Logger;

import java.util.List;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Validates the config of a stack by synthesising its cdktf plan into {@code <afon.cdktf>/stacks/<stack-id>},
 * eg {@code aws-keycloak}, without deploying it, eg to check that the config dir has all the properties the stack needs.
 * It overwrites the synthesised plan of any previous deploy of the stack in that dir, but not the tofu state file
 * {@code <afon.cdktf>/terraform.<stack-id>.tfstate}, as it doesn't run tofu.
 * <p>
 * The stacks are those of {@link StackDefinitions}.
 */
public class CloudStackValidator {

    private static final Logger  logger         = getLogger(CloudStackValidator.class);
    private static final String  STACK_SUFFIX   = "-stack";
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /**
     * @return the stacks, in order of their ids
     */
    public List<StackId> stacks() {
        return StackDefinitions.all().stream().map(StackDefinition::id).toList();
    }

    /**
     * @return the stack names, in kebab case, ie their ids, eg {@code azure-polaris}
     */
    public List<String> stackNames() {
        return stacks().stream().map(StackId::toString).toList();
    }

    /**
     * Resolves a stack name, case-insensitively, in kebab case, with or without the "stack" suffix,
     * eg "azure-polaris" or "azure-polaris-stack", or as a class simple name, eg "AzurePolarisStack".
     *
     * @return the stack, or {@code null} if not found
     */
    public StackId resolve(String name) {
        String kebabName = CAMEL_BOUNDARY.matcher(name).replaceAll("-").toLowerCase();
        if (kebabName.endsWith(STACK_SUFFIX)) {
            kebabName = kebabName.substring(0, kebabName.length() - STACK_SUFFIX.length());
        }
        try {
            StackId stackId = StackId.parse(kebabName);
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
     * Validates the stack by synthesising its plan into {@link #stackDir(StackId)}, from the config dir.
     *
     * @return the dir the stack was synthesised into
     * @throws RuntimeException if the stack fails to synthesise, eg because a property is missing
     */
    public String validate(StackId stackId) {
        return validate(stackId, new FilePropertyProvider());
    }

    /**
     * As {@link #validate(StackId)}, from the given config.
     */
    public String validate(StackId stackId, AfonPropertyProvider properties) {
        String stackDir = stackDir(stackId);
        logger.info("Validating stack {} by synthesising it into {}", stackId, stackDir);
        try {
            ComposedStack.synthesise(stackId, properties);
        } catch (RuntimeException e) {
            logger.error("Failed to validate stack {}", stackId, e);
            throw e;
        }
        logger.info("Validated stack {} by synthesising it into {}", stackId, stackDir);
        return stackDir;
    }

}
