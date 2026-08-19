package health.emrys.afon.cli;

import org.junit.jupiter.api.Test;

import java.util.SortedSet;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCommandsTest {

    private final OpsCommands opsCommands = new OpsCommands();

    @Test
    void resolvesKnownCommandsByKebabCaseName() {
        assertThat(opsCommands.resolve("configure-polaris-catalogs")).isEqualTo(health.emrys.afon.ops.ConfigurePolarisCatalogsMain.class);
        assertThat(opsCommands.resolve("deploy-aws-full-stack")).isEqualTo(health.emrys.afon.ops.DeployAwsFullStackMain.class);
    }

    @Test
    void resolvesKnownCommandsByRawClassNameCaseInsensitively() {
        assertThat(opsCommands.resolve("ConfigurePolarisCatalogsMain")).isEqualTo(health.emrys.afon.ops.ConfigurePolarisCatalogsMain.class);
        assertThat(opsCommands.resolve("configurepolariscatalogsmain")).isEqualTo(health.emrys.afon.ops.ConfigurePolarisCatalogsMain.class);
    }

    @Test
    void returnsNullForUnknownCommand() {
        assertThat(opsCommands.resolve("does-not-exist")).isNull();
    }

    @Test
    void namesListsOnlyKebabCaseFormAndIsSorted() {
        SortedSet<String> names = opsCommands.names();
        assertThat(names).contains("configure-polaris-catalogs", "deploy-aws-full-stack", "purge-polaris");
        assertThat(names).allMatch(name -> name.equals(name.toLowerCase()));
        assertThat(names).isEqualTo(new java.util.TreeSet<>(names));
    }

}
