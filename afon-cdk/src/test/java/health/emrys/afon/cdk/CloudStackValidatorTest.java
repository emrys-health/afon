package health.emrys.afon.cdk;

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
    void canListStacks() {
        assertThat(validator.stackNames()).containsExactly(
            "aws-full", "aws-keycloak", "aws-lab", "aws-polaris", "aws-snowflake",
            "azure-full", "azure-keycloak", "azure-polaris", "gcp-keycloak", "gcp-polaris");
    }

    @Test
    void canResolveStackByName() {
        StackId azurePolaris = new StackId(AZURE, "polaris");
        assertThat(validator.resolve("azure-polaris")).isEqualTo(azurePolaris);
        assertThat(validator.resolve("AZURE-POLARIS")).isEqualTo(azurePolaris);
        assertThat(validator.resolve("aws-full")).isEqualTo(new StackId(AWS, "full"));
        assertThat(validator.resolve("aws-keycloak")).isEqualTo(new StackId(AWS, "keycloak"));
    }

    @Test
    void canResolveStackByItsRoleInTheCloud() {
        assertThat(validator.resolve("aws-tre")).isEqualTo(new StackId(AWS, "full"));
        assertThat(validator.resolve("AZURE-CATALOG")).isEqualTo(new StackId(AZURE, "polaris"));
        assertThat(validator.resolve("gcp-identity")).isEqualTo(new StackId(GCP, "keycloak"));
        // no stack of gcp has the role, as for an unknown stack
        assertThat(validator.resolve("gcp-tre")).isNull();
    }

    @Test
    void canGetStackDirFromStackId() {
        assertThat(validator.stackDir(new StackId(GCP, "polaris"))).isEqualTo(stackDir(new StackId(GCP, "polaris")));
    }

    @Test
    void cannotResolveUnknownStack() {
        assertThat(validator.resolve("unknown")).isNull();
        assertThat(validator.resolve("aws-unknown")).isNull();
        assertThat(validator.resolve("mars-polaris")).isNull();
        assertThat(validator.resolve("gcp-full")).isNull();
        // the stacks are not classes, so not named as such
        assertThat(validator.resolve("azure-polaris-stack")).isNull();
        assertThat(validator.resolve("AzurePolarisStack")).isNull();
    }
}
