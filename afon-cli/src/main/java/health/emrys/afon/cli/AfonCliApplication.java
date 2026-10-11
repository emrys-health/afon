package health.emrys.afon.cli;

import health.emrys.afon.ops.help.CommandHelp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;

/**
 * Single executable entry point bundling all afon-ops commands. Dispatches
 * {@code java -jar afon-cli.jar <command> [args...]} to the matching afon-ops
 * {@code *Main} class, forwarding the remaining args to it unchanged.
 */
@SpringBootApplication
public class AfonCliApplication {

    private static final Logger logger = LoggerFactory.getLogger(AfonCliApplication.class);

    public static void main(String[] args) {
        new SpringApplicationBuilder(AfonCliApplication.class)
            .web(WebApplicationType.NONE)
            .run(args);
    }

    @Bean
    CommandLineRunner dispatch(OpsCommands opsCommands) {
        return args -> {
            if (args.length == 0 || isHelp(args[0])) {
                printUsage(opsCommands);
                return;
            }
            Class<?> target = opsCommands.resolve(args[0]);
            if (target == null) {
                if (args.length > 1 && isHelp(args[1])) {
                    printFilterUsage(opsCommands, args[0]);
                    return;
                }
                System.err.println("Unknown command: " + args[0]);
                printUsage(opsCommands);
                System.exit(1);
                return;
            }
            if (args.length > 1 && isHelp(args[1]) && printCommandHelp(target)) {
                return;
            }
            invoke(target, Arrays.copyOfRange(args, 1, args.length));
        };
    }

    private static boolean isHelp(String arg) {
        return "help".equalsIgnoreCase(arg) || "--help".equals(arg) || "-h".equals(arg);
    }

    /**
     * Handles {@code afon-cli <command> -h} by printing the docs of the command, if it has any, see
     * {@link CommandHelp}; otherwise the command is left to handle {@code -h} itself.
     *
     * @return whether the command had docs to print
     */
    private static boolean printCommandHelp(Class<?> target) {
        Optional<String> help = CommandHelp.find(OpsCommands.nameOf(target));
        help.ifPresent(System.out::println);
        return help.isPresent();
    }

    /**
     * Handles {@code afon-cli <filter> -h}, eg {@code afon-cli config -h}: "config" is not itself a
     * command, but is a shared filter of several (config-status, config-switch, ...), so list those
     * instead of just failing with "Unknown command".
     */
    private static void printFilterUsage(OpsCommands opsCommands, String filter) {
        Set<String> matches = opsCommands.namesWithFilter(filter);
        if (matches.isEmpty()) {
            System.out.println("No commands found matching '" + filter + "'.");
            return;
        }
        System.out.println("Commands matching '" + filter + "':");
        matches.forEach(name -> System.out.println("  " + name));
    }

    private static void printUsage(OpsCommands opsCommands) {
        System.out.println("Usage: afon-cli <command> [args...]");
        System.out.println("Commands:");
        opsCommands.names().forEach(name -> System.out.println("  " + name));
        if (cloudPropertySet()){
            String cloud = cloudPropertyValue();
            System.out.println("Commands specific to cloud: " + cloud);
            opsCommands.names(cloud).forEach(name -> System.out.println("  " + name));
        }
    }

    private static void invoke(Class<?> target, String[] args) {
        try {
            Method main = target.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            logger.error("Command {} failed", target.getSimpleName(), e.getCause());
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

}
