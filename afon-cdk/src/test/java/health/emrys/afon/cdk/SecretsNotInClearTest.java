package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tofu prints the attributes of every resource before it applies or destroys it, and the log of its output is kept, and
 * uploaded by the CI, so a secret that is an attribute in clear would be in the log.  Only what the provider marks as sensitive,
 * like the data of a Kubernetes secret, is printed as {@code (sensitive value)}.  So a secret may only be in the plan there:
 * not in an env var's value, nor in Helm values, nor in a ConfigMap, which is how a new plain {@code envVar(...)} with a password
 * would slip in.
 */
class SecretsNotInClearTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void noStackHasASecretInClear(@TempDir Path dir) throws Exception {
        // the template config has no secret values, only the names of the properties: each is set to a dummy value
        // that is recognised if it reaches the plan
        Map<String, String> secrets = new TreeMap<>();
        SynthesisSupport.secretPropertyNames().forEach(name -> secrets.put(name, SynthesisSupport.mockValueOf(name)));
        Map<String, String> overrides = new TreeMap<>(secrets);
        // which the stacks of some clouds need to be JSON
        overrides.put("GOOGLE_APPLICATION_CREDENTIALS", "{\"private_key\":\"" + secrets.get("GOOGLE_APPLICATION_CREDENTIALS") + "\"}");

        List<String> leaks = new ArrayList<>();
        int          i     = 0;
        for (StackDefinition definition : StackDefinitions.all()) {
            JsonNode plan;
            try {
                plan = JSON.readTree(SynthesisSupport.synthesise(definition.id(), dir.resolve("s" + i++), overrides));
            } catch (Exception e) {
                throw new AssertionError("Cannot synthesise " + definition.id(), e);
            }
            scan(definition.id().toString(), "", plan, secrets, leaks);
        }

        assertThat(leaks).as("secrets in clear in the plans, ie in tofu's output").isEmpty();
    }

    /**
     * @param path  where the node is in the plan
     * @param leaks the secrets found in clear, by the stack, the attribute, and the name of the secret
     */
    private static void scan(String stack, String path, JsonNode node, Map<String, String> secrets, List<String> leaks) {
        if (isSensitive(path)) {
            return;
        }
        if (node.isTextual()) {
            secrets.forEach((name, value) -> {
                if (!value.isBlank() && node.asText().contains(value)) {
                    leaks.add(stack + " " + path + " has " + name);
                }
            });
        } else if (node.isContainerNode()) {
            node.fields().forEachRemaining(e -> scan(stack, path + "/" + e.getKey(), e.getValue(), secrets, leaks));
            for (int i = 0; i < node.size() && node.isArray(); i++) {
                scan(stack, path + "[" + i + "]", node.get(i), secrets, leaks);
            }
        }
    }

    /**
     * @return whether tofu prints the attribute as sensitive: the data of a Kubernetes secret, or a Helm {@code set_sensitive}
     */
    private static boolean isSensitive(String path) {
        return path.matches("/resource/kubernetes_secret(_v1)?/[^/]+/(data|binary_data)(/.*)?")
               || path.matches("/resource/helm_release/[^/]+/set_sensitive(/.*|\\[.*)?");
    }
}
