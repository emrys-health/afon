package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.ConfigUtils.AFON_CDKTF_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test of the synthesised plan of each stack, to guard refactoring of the stacks: a change of the plan,
 * eg of a construct id, would make tofu destroy and recreate the live resource.
 * <p>
 * The plans are synthesised from the template config, with fixed values for what it leaves blank, and compared to
 * {@code src/test/resources/golden/<stack-id>.tf.json}.  After an intended change of a plan, regenerate the golden
 * files by running the test with {@code -Dgolden.update=true}.
 */
class StackSynthesisGoldenTest {

    private static final Path   GOLDEN_DIR           = Path.of("src/test/resources/golden");

    @ParameterizedTest
    @ValueSource(strings = {"aws-keycloak", "aws-polaris", "aws-jupyter", "aws-spark", "aws-trino", "aws-full",
                            "azure-polaris", "azure-full", "gcp-polaris"})
    void synthesisedPlanMatchesGolden(String stackName, @TempDir Path outDir) throws IOException {
        String plan = normalise(SynthesisSupport.synthesise(StackId.parse(stackName), outDir));

        Path golden = GOLDEN_DIR.resolve(stackName + ".tf.json");
        if (Boolean.getBoolean("golden.update") || System.getenv("GOLDEN_UPDATE") != null) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, plan);
        }
        assertThat(golden).as("golden file of %s, generate with -Dgolden.update=true", stackName).exists();
        assertThat(plan).isEqualTo(Files.readString(golden));
    }

    // The plan is normalised so that it is the same on each synthesis, and small enough to commit:
    // - the dependencies of a resource are sorted, as the order they are declared in does not matter to tofu
    // - object keys are sorted
    // - long strings, eg scripts, are reduced to their hash
    // The strings are otherwise the plan's own, including the JSON embedded in them, eg Helm values or kubectl
    // manifests, and the jars: tofu replaces what holds a string that differs, so the plan must be the same on each
    // synthesis, and the test fails, in some JVM runs, if it is not
    private static final ObjectMapper MAPPER           = new ObjectMapper();
    private static final int          MAX_STRING       = 500;

    static String normalise(String plan) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalise(MAPPER.readTree(plan))) + "\n";
    }

    private static JsonNode normalise(JsonNode node) throws IOException {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            for (var it = node.fields(); it.hasNext(); ) {
                var field = it.next();
                fields.put(field.getKey(), field.getValue());
            }
            for (var field : fields.entrySet()) {
                sorted.set(field.getKey(), "depends_on".equals(field.getKey())
                                           ? sortedTexts(normalise(field.getValue()))
                                           : normalise(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                array.add(normalise(element));
            }
            return array;
        }
        if (node.isTextual()) {
            return JsonNodeFactory.instance.textNode(normaliseText(node.asText()));
        }
        return node;
    }

    private static JsonNode sortedTexts(JsonNode dependencies) {
        ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
        java.util.stream.StreamSupport.stream(dependencies.spliterator(), false)
                                      .map(JsonNode::asText).sorted().forEach(sorted::add);
        return sorted;
    }

    private static String normaliseText(String text) {
        return abbreviate(text);
    }

    private static String abbreviate(String text) {
        return text.length() <= MAX_STRING ? text : String.format("<%d chars, sha256 %s>", text.length(), hash(text));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.ISO_8859_1)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
