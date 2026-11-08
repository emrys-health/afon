package health.emrys.afon.ops.scripts;

import ch.vorburger.exec.OutputStreamType;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessLogLevelsTest {

    private final ProcessLogLevels levels = new ProcessLogLevels();

    @Test
    void theConsoleLogsTheOutputFromInfoUp() {
        assertThat(ProcessLogLevels.consoleLogsOutput("INFO", null)).isTrue();
        assertThat(ProcessLogLevels.consoleLogsOutput("debug", null)).isTrue();
        assertThat(ProcessLogLevels.consoleLogsOutput(null, "INFO")).isTrue();
    }

    @Test
    void theConsoleLogsTheOutputByDefault() {
        // logback's default console level is INFO
        assertThat(ProcessLogLevels.consoleLogsOutput(null, null)).isTrue();
        assertThat(ProcessLogLevels.consoleLogsOutput(" ", "")).isTrue();
    }

    @Test
    void theConsoleDoesNotLogAllTheOutputFromWarnUp() {
        assertThat(ProcessLogLevels.consoleLogsOutput("OFF", null)).isFalse();
        assertThat(ProcessLogLevels.consoleLogsOutput("WARN", null)).isFalse();
        assertThat(ProcessLogLevels.consoleLogsOutput("error", null)).isFalse();
    }

    @Test
    void thePropertyWinsOverTheEnvVar() {
        assertThat(ProcessLogLevels.consoleLogsOutput("OFF", "INFO")).isFalse();
        assertThat(ProcessLogLevels.consoleLogsOutput("INFO", "OFF")).isTrue();
    }

    @Test
    void stdoutIsInfo() {
        assertThat(levels.dispatch(OutputStreamType.STDOUT, "Error: but on stdout")).isEqualTo(Level.INFO);
    }

    @Test
    void progressOnStderrIsInfo() {
        assertThat(levels.dispatch(OutputStreamType.STDERR, "Resolved 110 packages in 3ms")).isEqualTo(Level.INFO);
        assertThat(levels.dispatch(OutputStreamType.STDERR, "[NbClientApp] Executing notebook with kernel: afon"))
            .isEqualTo(Level.INFO);
    }

    @Test
    void warningsOnStderrAreWarn() {
        assertThat(levels.dispatch(OutputStreamType.STDERR, "[IPKernelApp] WARNING | Kernel is running over TCP"))
            .isEqualTo(Level.WARN);
    }

    @Test
    void errorsOnStderrAreError() {
        assertThat(levels.dispatch(OutputStreamType.STDERR, "Traceback (most recent call last):")).isEqualTo(Level.ERROR);
        assertThat(levels.dispatch(OutputStreamType.STDERR, "curl: (6) Could not resolve host: x, error")).isEqualTo(Level.ERROR);
        assertThat(levels.dispatch(OutputStreamType.STDERR, "Error from server (NotFound)")).isEqualTo(Level.ERROR);
    }
}
