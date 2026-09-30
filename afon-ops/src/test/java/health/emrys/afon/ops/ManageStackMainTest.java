package health.emrys.afon.ops;

import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackStepsFactory;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.ops.steps.StackAction.*;
import static org.assertj.core.api.Assertions.assertThat;

class ManageStackMainTest {

    private final StackStepsFactory factory = new StackStepsFactory();
    private final ManageStackMain manager = new ManageStackMain(factory);

    @Test
    void canResolveActionOnStackCaseInsensitively() {
        assertThat(manager.stepsProviderFor("deploy", "aws-keycloak"))
            .isSameAs(factory.stepsProviderFor(DEPLOY, StackId.parse("aws-keycloak")));
        assertThat(manager.stepsProviderFor("SETUP", "GCP-Polaris"))
            .isSameAs(factory.stepsProviderFor(SETUP, StackId.parse("gcp-polaris")));
    }

    @Test
    void cannotResolveUnknownActionOrStack() {
        assertThat(manager.stepsProviderFor("destroy", "aws-keycloak")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "aws-unknown")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "azure-full")).isNull();
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
