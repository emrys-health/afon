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
