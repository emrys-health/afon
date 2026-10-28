package health.emrys.afon.cdk.components;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KyvernoComponentTest {

    private static final String KEY = "-----BEGIN PUBLIC KEY-----\nabc\n-----END PUBLIC KEY-----";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(KyvernoComponent.Mode mode) {
        return (Map<String, Object>) KyvernoComponent.imagePolicy(KEY, mode).get("spec");
    }

    @SuppressWarnings("unchecked")
    @Test
    void auditPolicyRecordsTheUnsignedImagesOfTheNamespacesLabelledAudit() {
        Map<String, Object> policy = KyvernoComponent.imagePolicy(KEY, KyvernoComponent.Mode.AUDIT);
        Map<String, Object> spec = spec(KyvernoComponent.Mode.AUDIT);

        assertThat(policy).containsEntry("kind", "ImageValidatingPolicy").containsEntry("apiVersion", "policies.kyverno.io/v1");
        assertThat(((Map<String, Object>) policy.get("metadata")).get("name")).isEqualTo("emrys-verify-images-audit");
        assertThat(spec.get("validationActions")).isEqualTo(List.of("Audit"));
        assertThat(spec.get("failurePolicy")).isEqualTo("Ignore");
        assertThat(((Map<String, Object>) spec.get("matchConstraints")).get("namespaceSelector"))
            .isEqualTo(Map.of("matchLabels", Map.of(KyvernoComponent.NAMESPACE_LABEL, "audit")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void enforcePolicyRejectsThemAndFailsClosedOnTheNamespacesLabelledEnforce() {
        Map<String, Object> spec = spec(KyvernoComponent.Mode.ENFORCE);

        assertThat(spec.get("validationActions")).isEqualTo(List.of("Deny"));
        assertThat(spec.get("failurePolicy")).isEqualTo("Fail");
        assertThat(((Map<String, Object>) spec.get("matchConstraints")).get("namespaceSelector"))
            .isEqualTo(Map.of("matchLabels", Map.of(KyvernoComponent.NAMESPACE_LABEL, "enforce")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void bothCheckTheContainersAndInitContainersAgainstTheKey() {
        for (KyvernoComponent.Mode mode : KyvernoComponent.Mode.values()) {
            Map<String, Object> spec = spec(mode);
            var attestor = ((List<Map<String, Object>>) spec.get("attestors")).get(0);
            var cosign = (Map<String, Object>) attestor.get("cosign");
            assertThat(cosign.get("key")).isEqualTo(Map.of("data", KEY));
            assertThat(cosign.get("ctlog")).isEqualTo(Map.of("insecureIgnoreTlog", true, "insecureIgnoreSCT", true));
            assertThat(((List<Map<String, Object>>) spec.get("validations")).get(0).get("expression").toString())
                .contains("images.containers + images.initContainers").contains("verifyImageSignatures(image, [attestors.emrys])");
        }
    }

    @Test
    void enforcedNamespacesAreTrimmedAndWithoutDuplicates() {
        assertThat(KyvernoComponent.namespaces(" default, trino ,,default")).containsExactly("default", "trino");
        assertThat(KyvernoComponent.namespaces("")).isEmpty();
    }

    @Test
    void thePoliciesSurviveTheShellTheyAreAppliedThrough() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));

        // as the policies are written to kubectl by a provisioner, which runs sh, any quote in a policy must not end the word and its \\n must stay
        for (String json : List.of(
            health.emrys.afon.cdk.CdkUtils.toCanonicalJson(KyvernoComponent.imagePolicy(KEY, KyvernoComponent.Mode.AUDIT)),
            health.emrys.afon.cdk.CdkUtils.toCanonicalJson(KyvernoComponent.imagePolicy(KEY, KyvernoComponent.Mode.ENFORCE)),
            "{\"message\":\"the cluster's \\\"cloud\\\" $HOME `date` ''\"}")) {
            Process process = new ProcessBuilder("sh", "-c", KyvernoComponent.writeCommand(json)).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).stripTrailing();
            assertThat(process.waitFor()).isZero();
            assertThat(output).isEqualTo(json);
        }
    }

    @Test
    void thePoliciesHaveNoSingleQuoteToStartWith() {
        for (KyvernoComponent.Mode mode : KyvernoComponent.Mode.values()) {
            assertThat(health.emrys.afon.cdk.CdkUtils.toCanonicalJson(KyvernoComponent.imagePolicy(KEY, mode))).doesNotContain("'");
        }
    }
}
