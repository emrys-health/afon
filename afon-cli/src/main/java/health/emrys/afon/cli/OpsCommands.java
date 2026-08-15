package health.emrys.afon.cli;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Discovers the afon-ops {@code *Main} command classes (same naming convention already
 * used by afon-docs' DocumentAllMain) and resolves a command name to one of them.
 */
@Component
class OpsCommands {

    private static final String OPS_PACKAGE = "health.emrys.afon.ops";
    private static final String MAIN_SUFFIX = "Main";
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private final Map<String, Class<?>> commandsByName = new TreeMap<>();
    private final SortedSet<String> commandNames = new TreeSet<>();

    OpsCommands() {
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .acceptPackages(OPS_PACKAGE)
                .scan()) {
            scanResult.getAllClasses().stream()
                .filter(OpsCommands::isCommandClass)
                .forEach(this::register);
        }
    }

    private void register(ClassInfo info) {
        Class<?> commandClass = info.loadClass();
        if (!hasStaticMainMethod(commandClass)) {
            return;
        }
        String kebabName = kebabCase(commandClass.getSimpleName());
        commandsByName.put(kebabName, commandClass);
        commandsByName.put(commandClass.getSimpleName().toLowerCase(), commandClass);
        commandNames.add(kebabName);
    }

    private static boolean isCommandClass(ClassInfo info) {
        return info.isPublic()
            && !info.isAbstract()
            && !info.isInnerClass()
            && info.getSimpleName().endsWith(MAIN_SUFFIX);
    }

    private static boolean hasStaticMainMethod(Class<?> commandClass) {
        try {
            Method main = commandClass.getMethod("main", String[].class);
            return Modifier.isStatic(main.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Resolves a command name, accepted either in kebab-case (e.g. "deploy-aws-full-stack")
     * or as the raw class simple name (e.g. "DeployAwsFullStackMain"), case-insensitively.
     */
    Class<?> resolve(String command) {
        return commandsByName.get(command.toLowerCase());
    }

    SortedSet<String> names() {
        return commandNames;
    }

    private static String kebabCase(String simpleName) {
        String withoutSuffix = simpleName.substring(0, simpleName.length() - MAIN_SUFFIX.length());
        return CAMEL_BOUNDARY.matcher(withoutSuffix).replaceAll("-").toLowerCase();
    }

}
