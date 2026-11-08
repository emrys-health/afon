package health.emrys.afon.ops.scripts;

import ch.vorburger.exec.OutputStreamLogDispatcher;
import ch.vorburger.exec.OutputStreamType;
import org.slf4j.event.Level;

import java.util.regex.Pattern;

/**
 * The levels at which the output lines of the processes that we run are logged.  ManagedProcess logs every STDERR line
 * as an ERROR, but the tools that we run, eg uv and jupyter, write their progress to STDERR as well, so a successful
 * run would log errors.  STDERR lines are logged as ERROR only if they look like one, else as WARN or INFO; whether the
 * process failed is told by its exit value.
 */
public class ProcessLogLevels extends OutputStreamLogDispatcher {

    private static final Pattern ERROR   = Pattern.compile("\\b(error|fatal|exception|traceback|failed|failure)\\b",
                                                          Pattern.CASE_INSENSITIVE);
    private static final Pattern WARNING = Pattern.compile("\\bwarn(ing)?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @return whether the console shows the output lines of the processes that we run, as the logger logs them at INFO or above, so that
     * they need not also be written to the console as they are, which would show each twice.  The level is that of
     * {@code afon.logging.console}, as logback is configured with it, else the env var {@code AFON_LOGGING_CONSOLE}, else INFO, which
     * is logback's default.  At WARN or ERROR, or OFF, the lines are not all shown by the logger, so they are written as they are.
     */
    public static boolean consoleLogsOutput() {
        return consoleLogsOutput(System.getProperty("afon.logging.console"), System.getenv("AFON_LOGGING_CONSOLE"));
    }

    static boolean consoleLogsOutput(String property, String env) {
        String level = property != null && !property.isBlank() ? property : env;
        if (level == null || level.isBlank()) {
            return true;
        }
        return switch (level.trim().toUpperCase()) {
            case "INFO", "DEBUG", "TRACE", "ALL" -> true;
            default -> false;
        };
    }

    @Override
    public Level dispatch(OutputStreamType type, String line) {
        if (type == OutputStreamType.STDOUT) {
            return Level.INFO;
        }
        if (ERROR.matcher(line).find()) {
            return Level.ERROR;
        }
        return WARNING.matcher(line).find() ? Level.WARN : Level.INFO;
    }
}
