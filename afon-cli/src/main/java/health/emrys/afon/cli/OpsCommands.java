package health.emrys.afon.cli;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers the afon-ops {@code *Main} command classes and resolves a command name to one of them.
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

    Set<String> names() {
        return commandNames;
    }

    Set<String> names(String cloud) {
        if (cloud != null && !cloud.isBlank()){
            return commandNames.stream().filter(c -> c.contains(cloud)).collect(Collectors.toSet());
        }
        return commandNames;
    }

    /**
     * Commands under a given prefix, eg {@code namesWithPrefix("azure")} returns
     * {@code azure-account}, {@code azure-create}, etc. - every command that is either exactly
     * {@code prefix} or starts with {@code prefix + "-"}.
     */
    Set<String> namesWithPrefix(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        String dashPrefix = lowerPrefix + "-";
        return commandNames.stream()
            .filter(name -> name.equals(lowerPrefix) || name.startsWith(dashPrefix))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String kebabCase(String simpleName) {
        String withoutSuffix = simpleName.substring(0, simpleName.length() - MAIN_SUFFIX.length());
        return CAMEL_BOUNDARY.matcher(withoutSuffix).replaceAll("-").toLowerCase();
    }

}
