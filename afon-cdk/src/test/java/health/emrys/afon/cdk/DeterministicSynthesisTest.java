package health.emrys.afon.cdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that a stack synthesises to the very same plan in each JVM, and not only in one.
 * <p>
 * What goes into the plan must not depend on anything that differs from one run to the next, eg the iteration order of an
 * immutable {@link java.util.Map#of}, or the time that a jar was made: tofu sees a different string as a change, and
 * replaces what holds it, eg a {@code kubectl} manifest, on every deploy.  Within a JVM the order of those maps is the same,
 * so the plans are synthesised in JVMs of their own, as two runs agree by chance if there are few of them.
 */
class DeterministicSynthesisTest {

    private static final List<String> STACKS = List.of(
        "aws-keycloak", "aws-polaris", "aws-jupyter", "aws-spark", "aws-trino", "aws-full", "aws-snowflake",
        "azure-polaris", "azure-full", "gcp-polaris");

    @Test
    void planOfEachStackIsTheSameInEachJvm(@TempDir Path dir) throws Exception {
        Path first  = Files.createDirectory(dir.resolve("first"));
        Path second = Files.createDirectory(dir.resolve("second"));
        Path third  = Files.createDirectory(dir.resolve("third"));

        synthesiseInNewJvm(first);
        synthesiseInNewJvm(second);
        synthesiseInNewJvm(third);

        for (String stack : STACKS) {
            String plan = Files.readString(first.resolve(stack + ".tf.json"));
            assertThat(Files.readString(second.resolve(stack + ".tf.json")))
                .as("plan of %s in a second JVM", stack).isEqualTo(plan);
            assertThat(Files.readString(third.resolve(stack + ".tf.json")))
                .as("plan of %s in a third JVM", stack).isEqualTo(plan);
        }
    }

    private static void synthesiseInNewJvm(Path outDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            SynthesisSupport.class.getName(), outDir.toString()));
        command.addAll(STACKS);
        Path log = outDir.resolveSibling(outDir.getFileName() + ".log");
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("synthesis finished").isTrue();
        assertThat(process.exitValue()).as("exit of the synthesis, see " + Files.readString(log).lines().filter(l -> l.contains("Exception")).findFirst().orElse("its log")).isZero();
    }
}
