package health.emrys.afon.cdk;

import health.emrys.afon.cdk.aws.AwsFullStack;
import health.emrys.afon.cdk.aws.AwsKeycloakStack;
import health.emrys.afon.cdk.azure.AzurePolarisStack;
import health.emrys.afon.cdk.gcp.GcpPolarisStack;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.ConfigUtils.stackDir;
import static org.assertj.core.api.Assertions.assertThat;

class CloudStackValidatorTest {

    private final CloudStackValidator validator = new CloudStackValidator();

    @Test
    void canFindStacksOnClasspath() {
        assertThat(validator.stacks()).containsKeys("aws-full-stack", "aws-keycloak-stack", "azure-polaris-stack", "gcp-polaris-stack");
        // not public, so not runnable
        assertThat(validator.stacks()).doesNotContainKey("azure-kubernetes-stack");
        assertThat(validator.stacks().values()).allMatch(AfonCloudStack.class::isAssignableFrom);
        assertThat(validator.stackNames()).contains("aws-full", "aws-keycloak", "azure-polaris", "gcp-polaris");
    }

    @Test
    void canResolveStackByName() {
        assertThat(validator.resolve("azure-polaris")).isEqualTo(AzurePolarisStack.class);
        assertThat(validator.resolve("azure-polaris-stack")).isEqualTo(AzurePolarisStack.class);
        assertThat(validator.resolve("AzurePolarisStack")).isEqualTo(AzurePolarisStack.class);
        assertThat(validator.resolve("aws-full")).isEqualTo(AwsFullStack.class);
        assertThat(validator.resolve("aws-keycloak")).isEqualTo(AwsKeycloakStack.class);
    }

    @Test
    void canGetStackDirFromStackId() {
        assertThat(validator.stackDir(AzurePolarisStack.class)).isEqualTo(stackDir(new StackId(AZURE, "polaris")));
        assertThat(validator.stackDir(AwsFullStack.class)).isEqualTo(stackDir(new StackId(AWS, "full")));
        assertThat(validator.stackDir(AwsKeycloakStack.class)).isEqualTo(stackDir(new StackId(AWS, "keycloak")));
        assertThat(validator.stackDir(GcpPolarisStack.class)).isEqualTo(stackDir(new StackId(GCP, "polaris")));
    }

    @Test
    void stackNamesAreTheStackIds() {
        assertThat(validator.stackNames())
            .containsExactlyElementsOf(validator.stacks().values().stream().map(stack -> StackId.of(stack).toString()).toList());
    }

    @Test
    void cannotResolveUnknownStack() {
        assertThat(validator.resolve("unknown")).isNull();
    }
}
