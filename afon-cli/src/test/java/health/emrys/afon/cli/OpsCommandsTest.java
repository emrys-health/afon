package health.emrys.afon.cli;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCommandsTest {

    private final OpsCommands opsCommands = new OpsCommands();

    @Test
    void resolvesKnownCommandsByKebabCaseName() {
        assertThat(opsCommands.resolve("delete-aws-full-stack")).isEqualTo(health.emrys.afon.ops.DeleteAwsFullStackMain.class);
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
        Set<String> names = opsCommands.names("aws");
        assertThat(names).contains("deploy-aws-full-stack", "delete-aws-full-stack");
    }

}
