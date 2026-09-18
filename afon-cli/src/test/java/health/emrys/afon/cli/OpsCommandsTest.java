package health.emrys.afon.cli;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCommandsTest {

    private final OpsCommands opsCommands = new OpsCommands();

    @Test
    void resolvesKnownCommandsByKebabCaseName() {
        assertThat(opsCommands.resolve("deploy-aws-full-stack")).isEqualTo(health.emrys.afon.ops.DeployAwsFullStackMain.class);
    }

    @Test
    void resolvesKnownCommandsByRawClassNameCaseInsensitively() {
        assertThat(opsCommands.resolve("DeployAwsFullStackMain")).isEqualTo(health.emrys.afon.ops.DeployAwsFullStackMain.class);
        assertThat(opsCommands.resolve("deployawsfullstackmain")).isEqualTo(health.emrys.afon.ops.DeployAwsFullStackMain.class);
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
        assertThat(opsCommands.names("aws")).contains("deploy-aws-full-stack", "delete-aws-stack");
        assertThat(opsCommands.names("azure")).contains("deploy-azure-polaris-stack", "delete-azure-stack");
        assertThat(opsCommands.names("gcp")).contains("deploy-gcp-polaris-stack", "delete-gcp-stack");
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
