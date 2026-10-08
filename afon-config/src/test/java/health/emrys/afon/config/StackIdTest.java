package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackIdTest {

    @Test
    void canParseStackId() {
        assertThat(StackId.parse("aws-keycloak")).isEqualTo(new StackId(AWS, "keycloak"));
        assertThat(StackId.parse("GCP-Polaris")).isEqualTo(new StackId(GCP, "polaris"));
        assertThat(new StackId(AZURE, "polaris")).hasToString("azure-polaris");
        for (String invalid : List.of("aws", "aws-", "-keycloak", "mars-keycloak")) {
            assertThatThrownBy(() -> StackId.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void canStandForEveryStackOfCloud() {
        assertThat(StackId.all(AWS)).isEqualTo(StackId.parse("aws-all")).hasToString("aws-all");
        assertThat(StackId.all(AWS).isAll()).isTrue();
        assertThat(new StackId(AWS, "keycloak").isAll()).isFalse();
    }
}
