package health.emrys.afon.ops.smoke;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserPodNotebookSmokeMainTest {

    @Test
    void givesTheNotebooksOnlyWhatSmokeNotebookSetsForThemAndNotWhatIsBlank() {
        Map<String, String> environment = Map.of("AFON_TARGET", "diab-2026", "AFON_CATALOG", "aws", "AFON_FORBIDDEN_CATALOG", "", "PATH", "/usr/bin", "OIDC_TOKEN", "never-from-here",
                                                  "POLARIS_DOMAIN_NAME", "polaris.example", "OIDC_DOMAIN_NAME", "oidc.example");

        assertThat(UserPodNotebookSmokeMain.forwardedEnvironment(environment)).containsOnlyKeys("AFON_TARGET", "AFON_CATALOG", "POLARIS_DOMAIN_NAME", "OIDC_DOMAIN_NAME")
                                                                              .containsEntry("AFON_CATALOG", "aws");
    }

    @Test
    void givesSparkConnectAsTheHubDoesButWithNoTokenAndNoTrailingSlashForTheNotebookPutsOneAfterIt() {
        assertThat(UserPodNotebookSmokeMain.sparkRemoteWithoutToken()).startsWith("sc://").doesNotEndWith("/").doesNotContain(";");
    }
}
