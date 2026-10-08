package health.emrys.afon.ops;

import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManageStackMainTest {

    private final StackPlanFactory factory = new StackPlanFactory();
    private final ManageStackMain manager = new ManageStackMain(factory);

    @Test
    void canResolveActionOnStackCaseInsensitively() {
        assertThat(manager.stepsProviderFor("deploy", "aws-keycloak")).isNotNull();
        assertThat(manager.stepsProviderFor("SETUP", "GCP-Polaris", true)).isNotNull();
        assertThat(manager.stepsProviderFor("Delete", "AWS-All")).isNotNull();
    }

    @Test
    void canManageEveryStackDefined() {
        // including those that no command managed before, as they are made of components
        assertThat(manager.stepsProviderFor("deploy", "azure-full")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "aws-jupyter")).isNotNull();
        assertThat(manager.stepsProviderFor("delete", "aws-trino")).isNotNull();
    }

    @Test
    void canOnlyDeleteEveryStackOfACloud() {
        assertThat(manager.stepsProviderFor("deploy", "aws-all")).isNull();
        assertThat(manager.stepsProviderFor("setup", "aws-all")).isNull();
    }

    @Test
    void cannotResolveUnknownActionOrStack() {
        assertThat(manager.stepsProviderFor("destroy", "aws-keycloak")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "aws-unknown")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "azure-unknown")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "mars-keycloak")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "keycloak")).isNull();
    }

    @Test
    void stackIdsAreNamedAsValidateStackNamesTheStacks() {
        assertThat(new CloudStackValidator().stackNames())
            .containsAll(factory.supportedActions().keySet().stream()
                                .filter(stackId -> !stackId.isAll())
                                .map(StackId::toString)
                                .toList());
    }
}
