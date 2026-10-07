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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    private static final String TEMPLATES_CONFIG_DIR = "../templates/config";
    private static final Path   GOLDEN_DIR           = Path.of("src/test/resources/golden");

    /**
     * What the template config leaves blank, but the stacks need, set as system properties, which override the config
     * before its references are resolved, eg of ENV_NAME.  The properties that are optional are left blank.
     */
    private static final Map<String, String> FIXED_VALUES = fixedValues();

    private static Map<String, String> fixedValues() {
        Map<String, String> values = new TreeMap<>(Map.of(
            "ENV_NAME", "golden",
            "USER_ID", "mauro",
            "AWS_REGION", "eu-west-2",
            "AZURE_REGION", "uksouth",
            "AWS_POLARIS_DOMAIN_NAME", "polaris.golden.example",
            "AWS_JUPYTER_DOMAIN_NAME", "jupyter.golden.example",
            "AZURE_POLARIS_DOMAIN_NAME", "polaris-azure.golden.example",
            "AZURE_JUPYTER_DOMAIN_NAME", "jupyter-azure.golden.example",
            "GCP_POLARIS_DOMAIN_NAME", "polaris-gcp.golden.example",
            "GCP_JUPYTER_DOMAIN_NAME", "jupyter-gcp.golden.example"));
        for (String name : List.of(
            "AZURE_SUBSCRIPTION", "AWS_ACCOUNT_ID", "AWS_S3_ROLE_ARN", "AWS_GLUE_ROLE_ARN", "AWS_POLARIS_DNS_ZONE",
            "AWS_JUPYTER_DNS_ZONE", "AWS_KEYCLOAK_DNS_ZONE", "AWS_KEYCLOAK_CERT_ARN", "AWS_JUPYTER_CERT_ARN",
            "AWS_POLARIS_CERT_ARN", "GCP_PROJECT_ID", "GCP_SERVICE_ACCOUNT", "GCP_USER_ACCOUNT", "AZURE_TENANT_ID",
            "AZURE_APP_ID", "AZURE_APP_SECRET", "AZURE_SP_ID", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
            "POLARIS_DB_HOST", "POLARIS_DB_PASSWORD", "POLARIS_ID", "POLARIS_SECRET", "KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME",
            "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD", "KEYCLOAK_DB_PASSWORD", "GUACAMOLE_DB_HOST", "GUACAMOLE_DB_USERNAME",
            "GUACAMOLE_DB_PASSWORD", "OAUTH2_CLIENT_ID", "OAUTH2_CLIENT_SECRET", "OAUTH2_COOKIE_SECRET",
            "JUPYTER_VNC_PASSWORD")) {
            values.put(name, "golden-" + name.toLowerCase().replace('_', '-'));
        }
        return values;
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-keycloak", "aws-polaris", "aws-jupyter", "aws-spark", "aws-trino", "aws-full",
                            "azure-polaris", "azure-full", "gcp-polaris"})
    void synthesisedPlanMatchesGolden(String stackName, @TempDir Path outDir) throws IOException {
        String plan = synthesise(StackId.parse(stackName), outDir);

        Path golden = GOLDEN_DIR.resolve(stackName + ".tf.json");
        if (Boolean.getBoolean("golden.update") || System.getenv("GOLDEN_UPDATE") != null) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, plan);
        }
        assertThat(golden).as("golden file of %s, generate with -Dgolden.update=true", stackName).exists();
        assertThat(plan).isEqualTo(Files.readString(golden));
    }

    private static String synthesise(StackId stackId, Path outDir) throws IOException {
        Map<String, String> systemProperties = new TreeMap<>(FIXED_VALUES);
        // the tofu state path is part of the plan
        systemProperties.put(AFON_CDKTF_PROPERTY, "/golden/cdktf");
        systemProperties.forEach(System::setProperty);
        try {
            AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
            App app = new App(AppConfig.builder().outdir(outDir.toString()).build());
            new ComposedStack(app, StackDefinitions.get(stackId), properties);
            app.synth();
        } finally {
            systemProperties.keySet().forEach(System::clearProperty);
        }
        return normalise(Files.readString(outDir.resolve("stacks").resolve(stackId.toString()).resolve("cdk.tf.json")));
    }

    // The plan is normalised so that it is the same on each synthesis, and small enough to commit:
    // - the dependencies of a resource are sorted, as the order they are declared in does not matter to tofu
    // - object keys are sorted, including those of the JSON embedded in strings, eg Helm values or kubectl
    //   manifests, as they come from maps with a random iteration order
    // - zips, eg the Guacamole theme jar, are reduced to their entries, as they hold the time they were made
    // - long strings, eg scripts, are reduced to their hash
    private static final ObjectMapper MAPPER           = new ObjectMapper();
    private static final int          MAX_STRING       = 500;
    private static final Pattern      ECHOED_JSON      = Pattern.compile("^(echo ')(\\{.*})(' \\|.*)$", Pattern.DOTALL);

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

    private static String normaliseText(String text) throws IOException {
        String zip = zipEntries(text);
        if (zip != null) {
            return zip;
        }
        Matcher echoed = ECHOED_JSON.matcher(text);
        if (echoed.matches() && isJson(echoed.group(2))) {
            return abbreviate(echoed.group(1) + canonicalJson(echoed.group(2)) + echoed.group(3));
        }
        if (isJson(text)) {
            return abbreviate(canonicalJson(text));
        }
        return abbreviate(text);
    }

    private static boolean isJson(String text) {
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return false;
        }
        try {
            MAPPER.readTree(trimmed);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String canonicalJson(String json) throws IOException {
        return MAPPER.writeValueAsString(normalise(MAPPER.readTree(json.trim())));
    }

    /** @return the sorted names and CRCs of the entries if the text is a base64 zip, else null */
    private static String zipEntries(String text) {
        if (!text.startsWith("UEsD")) {
            return null;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text)))) {
            TreeMap<String, String> entries = new TreeMap<>();
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), hash(new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1)));
            }
            return "zip" + entries;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
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
