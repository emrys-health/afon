package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackNeedsMainTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void printsWhatAStackNeedsAsLinesOfNameAndValue() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(StackNeedsMain.lines(new String[]{"aws", "tre"}, properties)).contains(
            "AFON_STACK_ID=aws-full", "AFON_STACK_ROLE=tre",
            "AFON_STACK_DOMAINS=OIDC_DOMAIN_NAME AWS_POLARIS_DOMAIN_NAME AWS_JUPYTER_DOMAIN_NAME");
        assertThat(StackNeedsMain.lines(new String[]{"aws", "tre"}, properties))
            .anyMatch(line -> line.startsWith("AFON_STACK_COMPONENTS=Keycloak Polaris "));
        assertThat(StackNeedsMain.lines(new String[]{"AZURE", "azure-polaris"}, properties))
            .contains("AFON_STACK_ID=azure-polaris", "AFON_STACK_ROLE=catalog");
    }

    @Test
    void printsTheStacksThatCanServeAComponentWhenNoneIsGiven() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(StackNeedsMain.lines(new String[]{"aws", "none", "Keycloak"}, properties))
            .containsExactly("AFON_STACK_CANDIDATES=aws-keycloak");
        assertThat(StackNeedsMain.lines(new String[]{"gcp", "none", "Keycloak"}, properties))
            .containsExactly("AFON_STACK_CANDIDATES=");
    }

    @Test
    void rejectsWrongArgsAndStacksThatAreNotTheCloudsOwn() {
        assertThatThrownBy(() -> StackNeedsMain.lines(new String[]{"aws"}, properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage: stack-needs <cloud>");
        assertThatThrownBy(() -> StackNeedsMain.lines(new String[]{"aws", "none"}, properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage: stack-needs");
        assertThatThrownBy(() -> StackNeedsMain.lines(new String[]{"mars", "tre"}, properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown cloud 'mars', expected one of aws, azure or gcp");
        assertThatThrownBy(() -> StackNeedsMain.lines(new String[]{"gcp", "identity"}, properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No stack of gcp has the role 'identity'");
    }
}
