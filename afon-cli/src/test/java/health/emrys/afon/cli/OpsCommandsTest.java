package health.emrys.afon.cli;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCommandsTest {

    private final OpsCommands opsCommands = new OpsCommands();

    @Test
    void resolvesKnownCommandsByKebabCaseName() {
        assertThat(opsCommands.resolve("show-env")).isEqualTo(health.emrys.afon.ops.ShowEnvMain.class);
    }

    @Test
    void resolvesKnownCommandsByRawClassNameCaseInsensitively() {
        assertThat(opsCommands.resolve("ShowEnvMain")).isEqualTo(health.emrys.afon.ops.ShowEnvMain.class);
        assertThat(opsCommands.resolve("showenvmain")).isEqualTo(health.emrys.afon.ops.ShowEnvMain.class);
    }

    @Test
    void resolvesSubpackageMainCommands() {
        assertThat(opsCommands.resolve("generate-py-spark-context")).isNotNull();
        assertThat(opsCommands.resolve("config-status")).isNotNull();
    }

    @Test
    void doesNotExposeClassesNotNamedMain() {
        assertThat(opsCommands.resolve("exec-result")).isNull();
        assertThat(opsCommands.resolve("kube-client")).isNull();
    }

    @Test
    void returnsNullForUnknownCommand() {
        assertThat(opsCommands.resolve("unknown-command")).isNull();
    }

    @Test
    void namesListsOnlyKebabCaseFormAndIsSorted() {
        Set<String> names = opsCommands.names();
        assertThat(names).allMatch(name -> name.contains("-"));
        assertThat(names).allMatch(name -> name.equals(name.toLowerCase()));
        assertThat(names).isEqualTo(new java.util.TreeSet<>(names));
    }

    @Test
    void namesCanBeFilteredByCloud() {
        assertThat(opsCommands.names("aws")).contains("aws-login");
        assertThat(opsCommands.names("azure")).contains("azure-account");
        assertThat(opsCommands.names("gcp")).contains("gcp-account");
    }

    @Test
    void managingStacksIsOnlyByManageStack() {
        assertThat(opsCommands.names()).doesNotContain(
            "deploy-aws-full-stack", "deploy-aws-keycloak-stack", "deploy-aws-polaris-stack",
            "deploy-azure-polaris-stack", "deploy-gcp-polaris-stack",
            "setup-aws-full-stack", "setup-aws-keycloak-stack", "setup-aws-polaris-stack",
            "setup-azure-polaris-stack", "setup-gcp-polaris-stack",
            "deploy-aws-full-stack-and-azure-polaris-stack",
            "setup-aws-full-stack-with-azure-polaris-hosting-external-catalog");
    }

    @Test
    void deletingStacksIsOnlyByManageStack() {
        assertThat(opsCommands.names()).doesNotContain("delete-aws-stack", "delete-azure-stack", "delete-gcp-stack");
        assertThat(opsCommands.names()).contains("manage-stack");
    }

    @Test
    void namesReturnsAllNamesForNullOrBlankCloud() {
        assertThat(opsCommands.names((String) null)).isEqualTo(opsCommands.names());
        assertThat(opsCommands.names("")).isEqualTo(opsCommands.names());
    }

    @Test
    void namesWithFilterListsCommandsMatchingFilter() {
        Set<String> configNames = opsCommands.namesWithFilter("config");
        assertThat(configNames).contains("config-switch", "config-status", "config-init", "configure-users");
        assertThat(configNames).allMatch(name -> name.matches(opsCommands.regexFor("config")));
        assertThat(configNames).doesNotContain("aws-login");
    }

    @Test
    void namesWithFilterIsCaseInsensitive() {
        assertThat(opsCommands.namesWithFilter("CONFIG")).contains("config-switch");
    }

    @Test
    void namesWithFilterReturnsEmptySetForUnmatchedCommands() {
        assertThat(opsCommands.namesWithFilter("nonexistent")).isEmpty();
    }

}
