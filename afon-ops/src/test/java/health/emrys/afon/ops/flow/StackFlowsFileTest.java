package health.emrys.afon.ops.flow;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackRole;
import health.emrys.afon.ops.flow.StackFlowsFile.ItemSpec;
import health.emrys.afon.ops.steps.StackAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackFlowsFileTest {

    private final String flowsBefore = System.getProperty(StackFlowsFile.PROPERTY);

    @AfterEach
    void restoreFlows() {
        if (flowsBefore == null) {
            System.clearProperty(StackFlowsFile.PROPERTY);
        } else {
            System.setProperty(StackFlowsFile.PROPERTY, flowsBefore);
        }
    }

    private static void assertInvalid(String yaml, String message) {
        assertThatThrownBy(() -> StackFlowsFile.parse(yaml, "bad.yaml"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad.yaml").hasMessageContaining(message);
    }

    @Test
    void parsesTheBundledFlows() {
        Map<String, List<ItemSpec>> flows = StackFlowsFile.load(null);
        assertThat(flows.keySet()).containsExactly(
            "aws-full-and-azure-polaris", "aws-keycloak-and-polaris", "azure-keycloak-and-polaris", "gcp-keycloak-and-polaris",
            "azure-and-gcp-polaris", "cross-cloud-azure", "snowflake-reading-aws", "snowflake-reading-azure", "snowflake-reading-gcp",
            "snowflake-writing-azure", "snowflake-reading-all");
        List<ItemSpec> cross = flows.get("cross-cloud-azure");
        assertThat(cross).extracting(ItemSpec::action).containsExactly(StackAction.SETUP, StackAction.SETUP, StackAction.RUN);
        assertThat(cross.get(0).stack().role()).isEqualTo(StackRole.TRE);
        assertThat(cross.get(1).stack().cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(cross.get(2).scenario()).isEqualTo("external-azure");
    }

    @Test
    void stacksAreNamedByIdOrByRoleOrByComponent() {
        Map<String, List<ItemSpec>> flows = StackFlowsFile.parse("""
            flows:
              mine:
                - deploy: {stack: aws-lab}
                - setup: {cloud: gcp, role: catalog}
                - run: {cloud: aws, component: Snowflake, scenario: reading-aws}
            """, "test");
        List<ItemSpec> items = flows.get("mine");
        assertThat(items.get(0).stack().id()).hasToString("aws-lab");
        assertThat(items.get(1).stack().role()).isEqualTo(StackRole.CATALOG);
        assertThat(items.get(2).stack().component().getSimpleName()).isEqualTo("SnowflakeComponent");
    }

    @Test
    void rejectsInvalidFlows() {
        assertInvalid("nothing: here", "unknown 'nothing'");
        assertInvalid("flows:", "no 'flows' defined");
        assertInvalid("flows:\n  a: []", "at least one item");
        assertInvalid("flows:\n  a:\n    - hop: {stack: aws-x}", "unknown action 'hop'");
        assertInvalid("flows:\n  a:\n    - setup: {stack: aws-x}\n      delete: {stack: aws-x}", "one action");
        assertInvalid("flows:\n  a:\n    - run: {stack: aws-x}", "needs a scenario");
        assertInvalid("flows:\n  a:\n    - setup: {stack: aws-x, scenario: s}", "no scenario to run");
        assertInvalid("flows:\n  a:\n    - setup: {role: tre}", "needs a stack");
        assertInvalid("flows:\n  a:\n    - setup: {cloud: aws}", "needs a stack");
        assertInvalid("flows:\n  a:\n    - setup: {cloud: aws, role: tre, component: Git}", "needs a stack");
        assertInvalid("flows:\n  a:\n    - setup: {stack: aws-x, cloud: aws}", "by id");
        assertInvalid("flows:\n  a:\n    - setup: {cloud: mars, role: tre}", "unknown cloud 'mars'");
        assertInvalid("flows:\n  a:\n    - setup: {cloud: aws, role: boss}", "unknown role 'boss'");
        assertInvalid("flows:\n  a:\n    - setup: {cloud: aws, component: Nonesuch}", "unknown component 'Nonesuch'");
        assertInvalid("flows:\n  a:\n    - setup: {stack: nonsense}", "nonsense");
        assertInvalid("flows:\n  a:\n    - setup: {stack: aws-x, colour: red}", "unknown 'colour'");
        assertInvalid("flows:\n  a: [setup]\n  a: [delete]", "duplicate key");
        assertInvalid("flows: [", "bad.yaml");
    }

    @Test
    void fileIsTheOneNamedElseThatOfTheConfigDirElseTheBundledOne(@TempDir Path dir) throws IOException {
        Path inConfigDir = Files.writeString(dir.resolve(StackFlowsFile.FILE), "flows: {}");
        Path named = Files.writeString(dir.resolve("named.yaml"), "flows: {}");
        String configDir = dir.toString();
        assertThat(StackFlowsFile.file(null, configDir)).isEqualTo(inConfigDir);
        assertThat(StackFlowsFile.file(" ", configDir)).isEqualTo(inConfigDir);
        assertThat(StackFlowsFile.file(named.toString(), configDir)).isEqualTo(named);
        assertThat(StackFlowsFile.file(StackFlowsFile.BUNDLED, configDir)).isNull();
        assertThat(StackFlowsFile.file(null, dir.resolve("none").toString())).isNull();
        assertThatThrownBy(() -> StackFlowsFile.file(dir.resolve("none.yaml").toString(), configDir))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("none.yaml");
    }

    @Test
    void bundledFlowsCanBeExportedWithoutOverwriting(@TempDir Path dir) throws IOException {
        Path configDir = dir.resolve("config");
        Path file = StackFlows.exportBundled(configDir);
        assertThat(file).isEqualTo(configDir.resolve(StackFlowsFile.FILE));
        assertThat(StackFlowsFile.load(file).keySet()).isEqualTo(StackFlowsFile.load(null).keySet());
        Files.writeString(file, "mine");
        assertThatThrownBy(() -> StackFlows.exportBundled(configDir))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("exists already");
        assertThat(file).hasContent("mine");
    }

    @Test
    void userFlowsReplaceTheBundledOnesAndAreFoundByTheStacksOfTheStacksFile(@TempDir Path dir) throws IOException {
        System.setProperty(StackFlowsFile.PROPERTY, Files.writeString(dir.resolve("my-flows.yaml"), """
            flows:
              my-flow:
                - setup: {cloud: aws, role: identity}
                - setup: {stack: aws-lab}
              needs-a-stack-not-defined:
                - setup: {stack: aws-nonesuch}
            """).toString());

        assertThat(StackFlows.all()).extracting(StackFlow::name).containsExactly("my-flow");
        assertThat(StackFlows.find("my-flow").orElseThrow().items()).extracting(FlowItem::label)
            .containsExactly("setup aws-keycloak", "setup aws-lab");
        // the bundled flows are gone
        assertThat(StackFlows.find("cross-cloud-azure")).isEmpty();
        assertThat(StackFlows.isDefined("cross-cloud-azure")).isFalse();
        // a flow needing a stack that is not defined is defined, but cannot run, which running it says
        assertThat(StackFlows.isDefined("needs-a-stack-not-defined")).isTrue();
        assertThatThrownBy(() -> StackFlows.get("needs-a-stack-not-defined", new health.emrys.afon.ops.plan.StackPlanFactory(),
                                                new health.emrys.afon.config.FilePropertyProvider("../templates/config")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Flow 'needs-a-stack-not-defined' cannot run, as the stack 'aws-nonesuch' is not defined in the stacks of ")
            .hasMessageContaining("stacks-test.yaml");
        assertThatThrownBy(() -> StackFlows.get("no-such-flow", new health.emrys.afon.ops.plan.StackPlanFactory(),
                                                new health.emrys.afon.config.FilePropertyProvider("../templates/config")))
            .hasMessage("No such flow 'no-such-flow'");
    }

    @Test
    void invalidUserFileFailsNamingIt(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("bad-flows.yaml"), "flows:\n  a:\n    - hop: {stack: aws-x}\n");
        System.setProperty(StackFlowsFile.PROPERTY, file.toString());

        assertThatThrownBy(StackFlows::checkLoads)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(file.toString()).hasMessageContaining("unknown action");
    }
}
