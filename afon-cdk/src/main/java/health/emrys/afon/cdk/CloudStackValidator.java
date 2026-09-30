package health.emrys.afon.cdk;

import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Validates the config of a stack by synthesising its cdktf plan into {@code <afon.cdktf>/stacks/<stack-id>},
 * eg {@code aws-keycloak}, without deploying it, eg to check that the config dir has all the properties the stack needs.
 * It overwrites the synthesised plan of any previous deploy of the stack in that dir, but not the tofu state file
 * {@code <afon.cdktf>/terraform.<stack-id>.tfstate}, as it doesn't run tofu.
 * <p>
 * The stacks are the public {@link AfonCloudStack}s in {@value #CDK_PACKAGE} with a static main,
 * found on the classpath, so no source code is needed.
 */
public class CloudStackValidator {

    private static final Logger  logger         = getLogger(CloudStackValidator.class);
    private static final String  CDK_PACKAGE    = "health.emrys.afon.cdk";
    private static final String  STACK_SUFFIX   = "-stack";
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /**
     * The stacks keyed by kebab name, eg {@code azure-polaris-stack}.
     */
    private final Map<String, Class<? extends AfonCloudStack>> stacks = new TreeMap<>();

    public CloudStackValidator() {
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                // scan the non-public classes too, else the stacks extending a non-public
                // class, eg AzureKubernetesStack, are not found to implement AfonCloudStack
                .ignoreClassVisibility()
                .acceptPackages(CDK_PACKAGE)
                .scan()) {
            scanResult.getClassesImplementing(AfonCloudStack.class)
                      .filter(info -> info.isPublic() && !info.isAbstract() && !info.isInnerClass())
                      .loadClasses(AfonCloudStack.class)
                      .stream()
                      .filter(CloudStackValidator::hasStaticMainMethod)
                      .forEach(stack -> stacks.put(kebabCase(stack.getSimpleName()), stack));
        }
    }

    /**
     * @return the stacks keyed by kebab name, eg {@code azure-polaris-stack}
     */
    public Map<String, Class<? extends AfonCloudStack>> stacks() {
        return stacks;
    }

    /**
     * @return the stack names, in kebab case without the "stack" suffix, eg {@code azure-polaris}
     */
    public List<String> stackNames() {
        return stacks.keySet()
                     .stream()
                     .map(name -> name.substring(0, name.length() - STACK_SUFFIX.length()))
                     .toList();
    }

    /**
     * Resolves a stack name, case-insensitively, in kebab case, with or without the "stack" suffix,
     * eg "azure-polaris" or "azure-polaris-stack", or as the class simple name, eg "AzurePolarisStack".
     *
     * @return the stack class, or {@code null} if not found
     */
    public Class<? extends AfonCloudStack> resolve(String name) {
        String kebabName = kebabCase(name);
        if (!kebabName.endsWith(STACK_SUFFIX)) {
            kebabName = kebabName + STACK_SUFFIX;
        }
        return stacks.get(kebabName);
    }

    /**
     * Returns the dir the stack is synthesised into, ie {@code <afon.cdktf>/stacks/<stack-id>},
     * the {@link StackId} being named from the stack class, eg {@code aws-keycloak} for {@code AwsKeycloakStack}.
     */
    public String stackDir(Class<? extends AfonCloudStack> stack) {
        return ConfigUtils.stackDir(StackId.of(stack));
    }

    /**
     * Validates the stack by synthesising its plan into {@link #stackDir(Class)}.
     *
     * @return the dir the stack was synthesised into
     * @throws InvocationTargetException if the stack fails to synthesise, eg because a property is missing,
     *                                   with the failure as its cause
     */
    public String validate(Class<? extends AfonCloudStack> stack) throws ReflectiveOperationException {
        String stackDir = stackDir(stack);
        logger.info("Validating stack {} by synthesising it into {}", stack.getName(), stackDir);
        Method main = stack.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) new String[]{});
        } catch (InvocationTargetException e) {
            logger.error("Failed to validate stack {}", stack.getName(), e.getCause());
            throw e;
        }
        logger.info("Validated stack {} by synthesising it into {}", stack.getName(), stackDir);
        return stackDir;
    }

    private static boolean hasStaticMainMethod(Class<?> stack) {
        try {
            return Modifier.isStatic(stack.getMethod("main", String[].class).getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static String kebabCase(String name) {
        return CAMEL_BOUNDARY.matcher(name).replaceAll("-").toLowerCase();
    }

}
