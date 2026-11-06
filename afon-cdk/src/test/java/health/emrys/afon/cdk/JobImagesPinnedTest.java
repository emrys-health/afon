package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The images of the Jobs of the stacks must be pinned by digest.
 * <p>
 * The pod template of a Job cannot be updated.  On a cluster that verifies image signatures, Kyverno rewrites an unpinned
 * image to its digest on any update of a Job created before its namespace was under the policy, and the update is then
 * rejected as the template is immutable, including the update that the garbage collector makes to remove the finalizer of a
 * Job being deleted, which then stays stuck deleting and fails tofu-apply and tofu-destroy.  An image with a digest is not rewritten.
 */
class JobImagesPinnedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** which the gcp stacks need, for Loki to write its logs to the bucket */
    private static final Map<String, String> OVERRIDES = Map.of(
        "GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n\"}");

    private static void images(JsonNode node, List<String> found) {
        for (var field : node.properties()) {
            if ("image".equals(field.getKey()) && field.getValue().isTextual()) {
                found.add(field.getValue().asText());
            } else {
                images(field.getValue(), found);
            }
        }
        node.forEach(child -> {
            if (child.isContainerNode() && !node.isObject()) {
                images(child, found);
            }
        });
    }

    @Test
    void everyImageOfEveryJobOfTheFullStacksHasADigest(@TempDir Path dir) throws Exception {
        List<String> jobs = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();
        for (String stack : List.of("aws-full", "azure-full", "gcp-full")) {
            JsonNode resources = JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir.resolve(stack), OVERRIDES)).path("resource");
            for (var job : resources.path("kubernetes_job_v1").properties()) {
                List<String> images = new ArrayList<>();
                images(job.getValue(), images);
                jobs.add(stack + " " + job.getKey());
                assertThat(images).as("%s %s has images", stack, job.getKey()).isNotEmpty();
                images.stream().filter(image -> !image.contains("@sha256:")).forEach(image -> unpinned.add(stack + " " + job.getKey() + ": " + image));
            }
        }
        assertThat(jobs).as("the jobs found").isNotEmpty();
        assertThat(unpinned).as("images of Jobs without a digest, see the class doc").isEmpty();
    }
}
