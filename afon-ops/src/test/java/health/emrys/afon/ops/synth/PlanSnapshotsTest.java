package health.emrys.afon.ops.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanSnapshotsTest {

    private static final String PLAN = """
        {"resource": {"aws_eip": {"nat": {"tags": {"Name": "nat"}, "depends_on": ["a", "b"]}}},
         "provider": {"aws": [{"region": "eu-west-2"}]}}""";

    @TempDir
    Path dir;

    private Path plan(String where, String stack, String json) throws IOException {
        Path file = Files.createDirectories(dir.resolve(where).resolve(stack)).resolve("cdk.tf.json");
        return Files.writeString(file, json);
    }

    @Test
    void snapshotCopiesOnlyThePlansOfTheSynthesisedStacks() throws IOException {
        plan("stacks", "aws-keycloak", PLAN);
        plan("stacks", "aws-polaris", PLAN);
        Files.writeString(dir.resolve("stacks/aws-keycloak/other.json"), "{}");
        Files.createDirectories(dir.resolve("stacks/assets"));

        assertThat(PlanSnapshots.snapshot(dir.resolve("stacks"), dir.resolve("snap"), List.of()))
            .containsExactly("aws-keycloak", "aws-polaris");
        assertThat(dir.resolve("snap/aws-keycloak")).isDirectoryContaining(p -> p.getFileName().toString().equals("cdk.tf.json"));
        assertThat(dir.resolve("snap/aws-keycloak/other.json")).doesNotExist();
        assertThat(dir.resolve("snap/assets")).doesNotExist();
    }

    @Test
    void snapshotOfAStackThatIsNotSynthesisedFails() throws IOException {
        plan("stacks", "aws-keycloak", PLAN);
        assertThatThrownBy(() -> PlanSnapshots.snapshot(dir.resolve("stacks"), dir.resolve("snap"), List.of("aws-polaris")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aws-polaris");
    }

    @Test
    void samePlansAreTheSameWhateverTheOrderOfTheDependencies() throws IOException {
        plan("before", "aws-keycloak", PLAN);
        plan("after", "aws-keycloak", PLAN.replace("[\"a\", \"b\"]", "[\"b\", \"a\"]"));

        var comparison = PlanSnapshots.compare(dir.resolve("before"), dir.resolve("after"));

        assertThat(comparison.differs()).isFalse();
        assertThat(comparison.lines()).containsExactly("== aws-keycloak: unchanged", "The plans are the same");
    }

    @Test
    void renamedResourceIsGoneAndNew() throws IOException {
        plan("before", "aws-keycloak", PLAN);
        plan("after", "aws-keycloak", PLAN.replace("\"nat\": {", "\"nat2\": {"));

        var comparison = PlanSnapshots.compare(dir.resolve("before"), dir.resolve("after"));

        assertThat(comparison.differs()).isTrue();
        assertThat(comparison.lines()).contains("== aws-keycloak: CHANGED",
                                                "   - gone (tofu destroys it): aws_eip.nat",
                                                "   + new (tofu creates it):   aws_eip.nat2");
    }

    @Test
    void changedValueIsReportedWithItsPathAndLongValuesAreHashed() throws IOException {
        plan("before", "aws-keycloak", PLAN.replace("\"Name\": \"nat\"", "\"Name\": \"nat\", \"script\": \"" + "x".repeat(300) + "\""));
        plan("after", "aws-keycloak", PLAN.replace("\"Name\": \"nat\"", "\"Name\": \"other\", \"script\": \"" + "y".repeat(300) + "\""));

        var lines = PlanSnapshots.compare(dir.resolve("before"), dir.resolve("after")).lines();

        assertThat(lines).contains("     ~ resource.aws_eip.nat.tags.Name: nat -> other");
        assertThat(lines).anyMatch(line -> line.startsWith("     ~ resource.aws_eip.nat.tags.script: <300 chars, sha256 ")
                                           && line.length() < 120);
    }

    @Test
    void stackInOnlyOneDirDiffers() throws IOException {
        plan("before", "aws-keycloak", PLAN);
        plan("after", "aws-keycloak", PLAN);
        plan("after", "aws-polaris", PLAN);

        var comparison = PlanSnapshots.compare(dir.resolve("before"), dir.resolve("after"));

        assertThat(comparison.differs()).isTrue();
        assertThat(comparison.lines()).contains("== aws-polaris: only in " + dir.resolve("after"));
    }

    @Test
    void comparingADirWithNoPlansFails() throws IOException {
        plan("before", "aws-keycloak", PLAN);
        Files.createDirectories(dir.resolve("empty"));
        assertThatThrownBy(() -> PlanSnapshots.compare(dir.resolve("before"), dir.resolve("empty")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
    }
}
