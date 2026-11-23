package health.emrys.afon.ops.steps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resuming a run of steps from one, and saying which step failed.
 */
class StepRunnerTest {

    @TempDir
    Path dir;

    private final List<String> ran = new ArrayList<>();

    private Step step(String name) {
        return new Step(name, name, () -> ran.add(name));
    }

    private Step failing(String name) {
        return new Step(name, name, () -> {
            throw new IllegalStateException("broken " + name);
        });
    }

    @Test
    void resumesFromAStepWithoutRunningTheOnesBefore() {
        new StepRunner(null, null).runStepsStartingWith(List.of(step("deploy"), step("wait"), step("configure"), step("complete")), "configure");

        assertThat(ran).containsExactly("configure", "complete");
    }

    @Test
    void failsWhenTheStepToResumeFromIsNotOneOfThem() {
        assertThatThrownBy(() -> new StepRunner(null, null).runStepsStartingWith(List.of(step("deploy"), step("configure")), "confugure"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No step confugure to resume from").hasMessageContaining("deploy, configure");
        assertThat(ran).isEmpty();
    }

    @Test
    void writesTheNameOfTheStepThatFailedToTheFileItIsGiven() throws IOException {
        Path file = dir.resolve("failed-step");

        assertThatThrownBy(() -> new StepRunner(null, file).runSteps(List.of(step("deploy"), failing("configure"), step("complete"))))
            .isInstanceOf(RuntimeException.class);

        assertThat(ran).containsExactly("deploy");
        assertThat(Files.readString(file)).isEqualTo("configure");
    }

    @Test
    void writesNothingWhenNoStepFails() {
        Path file = dir.resolve("failed-step");

        new StepRunner(null, file).runSteps(List.of(step("deploy"), step("complete")));

        assertThat(file).doesNotExist();
    }
}
