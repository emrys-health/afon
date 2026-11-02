package health.emrys.afon.cdk.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loki keeps its logs in the bucket of the "gcp" catalog, with the credentials of {@code GOOGLE_APPLICATION_CREDENTIALS}, which the
 * chart's {@code gcs} config takes no place for, so that they are set on the chunk store and the ruler by extra values.
 */
class GcpLogStorageTest {

    private static final ObjectMapper JSON        = new ObjectMapper();
    private static final String       CREDENTIALS = "{\"type\":\"service_account\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n\"}";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lokiValues(Path dir, Map<String, String> overrides) throws Exception {
        JsonNode plan = JSON.readTree(SynthesisSupport.synthesise(StackId.parse("gcp-full"), dir, overrides));
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode value : plan.path("resource").path("helm_release").path("lokiRelease").path("values")) {
            values.add(new Yaml().load(value.asText()));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> values, String... path) {
        Object node = values;
        for (String key : path) {
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    @Test
    void lokiUsesTheGcsBucketOfTheGcpCatalog(@TempDir Path dir) throws Exception {
        List<Map<String, Object>> values = lokiValues(dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", CREDENTIALS));

        assertThat(at(values.get(0), "loki", "storage", "type")).isEqualTo("gcs");
        assertThat(at(values.get(0), "loki", "storage", "bucketNames", "chunks")).isEqualTo("golden-afon-bucket");
        assertThat(at(values.get(0), "loki", "schemaConfig", "configs")).asString().contains("object_store=gcs");
    }

    @Test
    void theChunkStoreAndTheRulerAreGivenTheServiceAccount(@TempDir Path dir) throws Exception {
        List<Map<String, Object>> values = lokiValues(dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", CREDENTIALS));

        assertThat(values).hasSize(2);
        // exactly as it is configured, newlines of the key included, as it is a YAML string and not YAML
        assertThat(at(values.get(1), "loki", "storage_config", "gcs", "service_account")).isEqualTo(CREDENTIALS);
        assertThat(at(values.get(1), "loki", "storage_config", "gcs", "bucket_name")).isEqualTo("golden-afon-bucket");
        assertThat(at(values.get(1), "loki", "rulerConfig", "storage", "gcs", "service_account")).isEqualTo(CREDENTIALS);
        assertThat(at(values.get(1), "loki", "rulerConfig", "storage", "type")).isEqualTo("gcs");
    }

    @Test
    void theMonitoringNeedsTheCredentials(@TempDir Path dir) {
        assertThatThrownBy(() -> lokiValues(dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("GOOGLE_APPLICATION_CREDENTIALS is not set, which the monitoring needs for Loki to write its logs to the bucket");
    }
}
