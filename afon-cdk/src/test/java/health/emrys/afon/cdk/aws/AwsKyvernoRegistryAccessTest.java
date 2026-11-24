package health.emrys.afon.cdk.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kyverno reads the signatures of the images from ECR through an IRSA role of its service accounts, which has no access to the KMS key.
 */
class AwsKyvernoRegistryAccessTest {

    private static final ObjectMapper JSON     = new ObjectMapper();
    private static final String       REGISTRY = "123456789012.dkr.ecr.eu-west-2.amazonaws.com/emrys";

    @Test
    void theRolePullsFromTheRepositoriesUnderThePathOnlyAndHasNoKmsAccess() throws Exception {
        JsonNode policy = JSON.readTree(AwsCloudProvider.ecrReadPolicy("eu-west-2", "123456789012", "emrys"));

        assertThat(policy.path("Statement").get(0).path("Action").asText()).isEqualTo("ecr:GetAuthorizationToken");
        assertThat(policy.path("Statement").get(1).path("Resource").asText()).isEqualTo("arn:aws:ecr:eu-west-2:123456789012:repository/emrys/*");
        assertThat(policy.toString()).doesNotContain("kms:").doesNotContain("ecr:Put").doesNotContain("ecr:*");
        assertThat(JSON.readTree(AwsCloudProvider.ecrReadPolicy("eu-west-2", "123456789012", "")).path("Statement").get(1).path("Resource").asText())
            .isEqualTo("arn:aws:ecr:eu-west-2:123456789012:repository/*");
    }

    @Test
    void theServiceAccountsOfKyvernoAreAnnotatedWithTheRoleOnlyForAnEcr(@TempDir Path dir, @TempDir Path other) throws Exception {
        JsonNode with = JSON.readTree(SynthesisSupport.synthesise(StackId.parse("aws-full"), dir, Map.of("AWS_IMAGE_REGISTRY", REGISTRY)));
        JsonNode role = kyvernoRole(with);
        assertThat(role.isMissingNode()).isFalse();
        // any of the three service accounts that verify images
        assertThat(role.path("assume_role_policy").asText()).contains("system:serviceaccount:kyverno:kyverno-admission-controller",
                                                                       "system:serviceaccount:kyverno:kyverno-background-controller",
                                                                       "system:serviceaccount:kyverno:kyverno-reports-controller");
        String release = with.path("resource").path("helm_release").toString();
        assertThat(release).contains("admissionController.rbac.serviceAccount.annotations.eks\\\\.amazonaws\\\\.com/role-arn",
                                     "backgroundController.rbac.serviceAccount.annotations", "reportsController.rbac.serviceAccount.annotations");

        JsonNode without = JSON.readTree(SynthesisSupport.synthesise(StackId.parse("aws-full"), other, Map.of()));
        assertThat(kyvernoRole(without).isMissingNode()).isTrue();
    }

    private static JsonNode kyvernoRole(JsonNode plan) {
        for (var entry : plan.path("resource").path("aws_iam_role").properties()) {
            if (entry.getKey().contains("kyvernoRegistryRole")) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }
}
