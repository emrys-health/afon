package health.emrys.afon.ops.steps;

import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.flow.StackFlow;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.plan.StackPlanFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What depends on the roles of the stacks, not on their ids, works with a stacks file whose ids are other than the bundled ones.
 */
class StackIdsAreFreeTest {

    private static final String OTHER_IDS = """
        stacks:
          aws-who:
            role: identity
            components: [Keycloak]
          aws-what:
            role: catalog
            components: [Polaris]
          aws-all-of-it:
            role: tre
            components:
              - name: Keycloak
                unless: KEYCLOAK_EXTERNAL
              - Polaris
              - Jupyter
              - NetworkPolicies
              - SparkOperator
              - Spark
              - CrdCleanup
          aws-with-sf:
            extends: aws-all-of-it
            components: [Snowflake]
          azure-who:
            role: identity
            components: [Keycloak]
          azure-what:
            role: catalog
            components: [Polaris]
          gcp-what:
            role: catalog
            components: [Polaris]
        """;

    private final String stacksBefore = System.getProperty(StackDefinitions.STACKS_PROPERTY);
    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
    private final List<String> calls = new ArrayList<>();
    private final StepOperations operations = new StepOperations(new StepActions() {
        @Override
        void checkAnswers(String url, String failure) {
            calls.add(failure);
        }

        @Override
        protected void checkAnswers(String url, String failure, Set<Integer> codes) {
            calls.add(failure);
        }
    }, properties, new FileUserProvider());

    @AfterEach
    void restoreStacks() {
        if (stacksBefore == null) {
            System.clearProperty(StackDefinitions.STACKS_PROPERTY);
        } else {
            System.setProperty(StackDefinitions.STACKS_PROPERTY, stacksBefore);
        }
    }

    private void useStacks(Path dir, String yaml) throws IOException {
        System.setProperty(StackDefinitions.STACKS_PROPERTY, Files.writeString(dir.resolve("other-stacks.yaml"), yaml).toString());
    }

    @Test
    void flowsAreOfTheStacksWithTheRolesWhateverTheirIds(@TempDir Path dir) throws IOException {
        useStacks(dir, OTHER_IDS);

        assertThat(StackFlows.all()).extracting(StackFlow::name).contains(
            "aws-full-and-azure-polaris", "aws-keycloak-and-polaris", "azure-keycloak-and-polaris", "azure-and-gcp-polaris",
            "cross-cloud-azure", "snowflake-reading-aws", "snowflake-reading-all");
        assertThat(labels("cross-cloud-azure")).containsExactly(
            "setup aws-all-of-it", "setup azure-what", "run aws-all-of-it external-azure");
        assertThat(labels("aws-keycloak-and-polaris")).containsExactly("setup aws-who", "setup aws-what");
        assertThat(labels("azure-and-gcp-polaris")).containsExactly("setup azure-what", "setup gcp-what");
        // the stack with Snowflake has no role, and is found by its component
        assertThat(labels("snowflake-reading-all")).containsExactly(
            "run aws-with-sf reading-aws", "run aws-with-sf reading-azure", "run aws-with-sf reading-gcp");
    }

    @Test
    void flowsAreValidWhateverTheIdsOfTheirStacks(@TempDir Path dir) throws IOException {
        useStacks(dir, OTHER_IDS);
        properties.setValue(AfonProperty.AWS_KEYCLOAK_EXTERNAL, "false");

        StackPlanFactory factory = new StackPlanFactory();
        StackFlows.all().forEach(flow -> flow.validate(factory, properties));
        assertThat(StackFlows.get("cross-cloud-azure", factory, properties).items()).hasSize(3);
    }

    @Test
    void flowsOfStacksTheFileDoesNotDefineCannotRun(@TempDir Path dir) throws IOException {
        useStacks(dir, """
            stacks:
              aws-who:
                role: identity
                components: [Keycloak]
              aws-what:
                role: catalog
                components: [Polaris]
            """);

        assertThat(StackFlows.all()).extracting(StackFlow::name).containsExactly("aws-keycloak-and-polaris");
        assertThat(StackFlows.find("cross-cloud-azure")).isEmpty();
        // but running it says why, and it is not mistaken for a stack
        assertThat(StackFlows.isDefined("cross-cloud-azure")).isTrue();
        assertThatThrownBy(() -> StackFlows.get("cross-cloud-azure", new StackPlanFactory(), properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Flow 'cross-cloud-azure' cannot run, as the tre stack of aws is not defined in the stacks of ");
        assertThat(StackFlows.find("snowflake-reading-aws")).isEmpty();
    }

    @Test
    void answerChecksSayWhichStackToSetUpByItsRoleNotItsId(@TempDir Path dir) throws Throwable {
        useStacks(dir, OTHER_IDS);
        properties.setValue(AfonProperty.OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(AfonProperty.AWS_POLARIS_DOMAIN_NAME, "polaris.example");

        operations.checkKeyCloakAnswers(AfonCloud.AWS).getFunction().run();
        // GCP has no identity stack, so the one of another cloud serves it
        operations.checkKeyCloakAnswers(AfonCloud.GCP).getFunction().run();
        operations.checkPolarisAnswers(AfonCloud.AWS).getFunction().run();

        assertThat(calls).containsExactly(
            "Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first, eg: afon-cli.sh manage-stack setup aws-who",
            "Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first, eg: afon-cli.sh manage-stack setup aws-who",
            "Set up the aws Polaris stack first, eg: afon-cli.sh manage-stack setup aws-what");
    }

    @Test
    void answerChecksStillWorkWithoutAStackWithTheRole(@TempDir Path dir) throws Throwable {
        useStacks(dir, "stacks:\n  aws-lonely:\n    components: [Git]\n");
        properties.setValue(AfonProperty.OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(AfonProperty.AWS_POLARIS_DOMAIN_NAME, "polaris.example");

        operations.checkKeyCloakAnswers(AfonCloud.AWS).getFunction().run();
        operations.checkPolarisAnswers(AfonCloud.AWS).getFunction().run();

        assertThat(calls).containsExactly("Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first",
                                          "Set up the aws Polaris stack first");
    }

    private static List<String> labels(String flow) {
        return StackFlows.find(flow).orElseThrow().items().stream().map(item -> item.label()).toList();
    }
}
