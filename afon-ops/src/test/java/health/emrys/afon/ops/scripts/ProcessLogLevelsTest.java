package health.emrys.afon.ops.scripts;

import ch.vorburger.exec.OutputStreamType;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessLogLevelsTest {

    private final ProcessLogLevels levels = new ProcessLogLevels();

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
