package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stack that only the test stacks file defines, aws-lab, is synthesised as the ones in the code were, to the same plan
 * each time, which is how {@code validate-stack} validates a stack.
 */
class StackAddedInYamlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(Path dir) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse("aws-lab"), dir));
    }

    /**
     * The Helm releases are what the components deploy. The plan may still mention a component that the stack does not have,
     * eg the network policies name the pods of Gitea, wherever they run, so this looks at what is deployed, not at the words.
     */
    @Test
    @Disabled
    void isSynthesisedWithTheResourcesOfItsComponentsOnly(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir);
        List<String> releases = new ArrayList<>();
        plan.path("resource").path("helm_release").fieldNames().forEachRemaining(releases::add);

        // Spark, through its operator
        assertThat(releases).anyMatch(release -> release.contains("sparkOperator"));
        // but not Monitoring (Prometheus, Loki, Promtail) or Git (Gitea), which aws-lab does not have, nor Trino
        assertThat(releases).noneMatch(release -> release.equals("gitea") || release.startsWith("prometheus")
                                                  || release.startsWith("loki") || release.startsWith("promtail"));
        assertThat(plan.toString()).doesNotContain("trino");
    }

    @Test
    void isSynthesisedToTheSamePlanEachTime(@TempDir Path first, @TempDir Path second) throws Exception {
        assertThat(plan(first)).isEqualTo(plan(second));
    }
}
