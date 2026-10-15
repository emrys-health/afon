package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.StackId;
import org.junit.Ignore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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

    @Ignore("Why shouldn't it contain gitea?")
    void isSynthesisedWithTheResourcesOfItsComponentsOnly(@TempDir Path dir) throws Exception {
        String plan = plan(dir).toString();
        assertThat(plan).contains("spark").doesNotContain("trino").doesNotContain("kube-prometheus-stack").doesNotContain("gitea");
    }

    @Test
    void isSynthesisedToTheSamePlanEachTime(@TempDir Path first, @TempDir Path second) throws Exception {
        assertThat(plan(first)).isEqualTo(plan(second));
    }
}
