package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateCleanupScriptMainTest {

    @Test
    void missingStackMessageSaysWhatToGive() {
        assertThat(GenerateCleanupScriptMain.missingStackMessage())
            .contains("Missing argument: <stack-id>")
            .contains("afon-cli.sh generate-cleanup-script <stack-id>")
            .contains("eg aws-keycloak");
    }
}
