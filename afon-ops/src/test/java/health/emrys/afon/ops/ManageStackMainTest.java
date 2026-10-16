package health.emrys.afon.ops;

import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
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
    void canNameAStackByItsRoleInTheCloudAsWellAsByItsId() {
        assertThat(manager.stepsProviderFor("setup", "aws-tre")).isNotNull();
        assertThat(manager.stepsProviderFor("deploy", "AZURE-Catalog", true)).isNotNull();
        assertThat(manager.stepsProviderFor("delete", "aws-identity")).isNotNull();
        // no stack of gcp has the role identity
        assertThat(manager.stepsProviderFor("setup", "gcp-identity")).isNull();
        // the scenarios of the stack with the role are those of the stack: the snowflake stack has no role, the full one has none
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        assertThat(manager.runProviderFor("aws-tre", "external-azure", properties) != null)
            .isEqualTo(manager.runProviderFor("aws-full", "external-azure", properties) != null);
        assertThat(manager.runProviderFor("aws-tre", "reading-azure", properties)).isNull();
    }

    @Test
    void canManageEveryStackDefined() {
        // including those that no command managed before, as they are made of components
        assertThat(manager.stepsProviderFor("deploy", "azure-full")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "aws-keycloak")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "aws-lab")).isNotNull();
        assertThat(manager.stepsProviderFor("delete", "aws-snowflake")).isNotNull();
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
    void runDistinguishesAFlowFromAStack() {
        assertThat(manager.isFlow("cross-cloud-azure")).isTrue();
        assertThat(manager.isFlow("aws-full")).isFalse();
        assertThat(manager.isFlow("aws-snowflake")).isFalse();
        assertThat(manager.isFlow("unknown")).isFalse();
    }

    @Test
    void canResolveScenarioOfStack() {
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        assertThat(manager.runProviderFor("aws-snowflake", "reading-azure", properties)).isNotNull();
        assertThat(manager.runProviderFor("aws-snowflake", "reading-mars", properties)).isNull();
        assertThat(manager.runProviderFor("aws-full", "reading-azure", properties)).isNull();
        assertThat(manager.runProviderFor("aws-unknown", "reading-azure", properties)).isNull();
        assertThat(manager.runProviderFor("snowflake", "reading-azure", properties)).isNull();
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
