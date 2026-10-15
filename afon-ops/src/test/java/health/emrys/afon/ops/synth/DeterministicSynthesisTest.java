package health.emrys.afon.ops.synth;

import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicSynthesisTest {

    private static final StackId STACK = StackId.parse("aws-keycloak");

    @TempDir
    Path stacksDir;

    private void plan(String json) throws IOException {
        Files.writeString(Files.createDirectories(stacksDir.resolve(STACK.toString())).resolve("cdk.tf.json"), json);
    }

    @Test
    void samePlanInEachRunHasNoDifferences() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<String> differences = DeterministicSynthesis.check(STACK, stacksDir, 3, stack -> {
            runs.incrementAndGet();
            plan("{\"resource\": {\"aws_eip\": {\"nat\": {\"name\": \"nat\"}}}}");
        });

        assertThat(differences).isEmpty();
        assertThat(runs).hasValue(3);
    }

    @Test
    void planThatDiffersInARunIsReportedWithoutItsValues() throws Exception {
        AtomicInteger run = new AtomicInteger();
        List<String> differences = DeterministicSynthesis.check(STACK, stacksDir, 3, stack ->
            plan("{\"resource\": {\"aws_eip\": {\"nat\": {\"password\": \"secret-" + (run.incrementAndGet() == 3 ? "c" : "a") + "\"}}}}"));

        assertThat(differences).anyMatch(line -> line.startsWith("Run 1 and run 3 differ"))
                               .anyMatch(line -> line.contains("resource.aws_eip.nat.password"))
                               .noneMatch(line -> line.contains("secret-"))
                               .noneMatch(line -> line.startsWith("Run 1 and run 2"));
    }

    @Test
    void needsTwoRunsToCompare() {
        assertThatThrownBy(() -> DeterministicSynthesis.check(STACK, stacksDir, 1, stack -> plan("{}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedSynthesisIsPropagated() {
        assertThatThrownBy(() -> DeterministicSynthesis.check(STACK, stacksDir, 3, stack -> {
            throw new IOException("Synthesis failed");
        })).isInstanceOf(IOException.class).hasMessage("Synthesis failed");
    }
}
