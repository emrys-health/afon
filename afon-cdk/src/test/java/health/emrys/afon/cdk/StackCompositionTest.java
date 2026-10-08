package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.CrdCleanupComponent;
import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.cdk.components.SparkOperatorComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks that the stacks are composed correctly, ie that each component has what it declares to need, without
 * synthesising them.  {@link StackSynthesisGoldenTest} checks what they synthesise.
 */
class StackCompositionTest {

    private static final StackId STACK = new StackId(AWS, "test");

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void everyStackIsComposedCorrectlyWhetherOrNotItDeploysItsOwnKeyCloak() {
        for (String external : List.of("false", "true")) {
            properties.setValue(AWS_KEYCLOAK_EXTERNAL, external);
            for (StackDefinition definition : StackDefinitions.all()) {
                assertThatCode(() -> definition.componentsFor(properties))
                    .as("%s with AWS_KEYCLOAK_EXTERNAL=%s", definition.id(), external)
                    .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void componentsOfAStackDoNotRepeat() {
        for (StackDefinition definition : StackDefinitions.all()) {
            List<Class<?>> classes = definition.componentsFor(properties).stream().<Class<?>>map(Object::getClass).toList();
            assertThat(classes).as(definition.id().toString()).doesNotHaveDuplicates();
        }
    }

    @Test
    void componentsDeclareWhatTheyNeed() {
        assertThat(new PolarisComponent().after()).containsExactly(KeycloakComponent.class);
        assertThat(new JupyterComponent().after()).containsExactly(KeycloakComponent.class);
        assertThat(new SparkComponent().requires()).containsExactly(SparkOperatorComponent.class);
        assertThat(new MonitoringComponent().requires()).containsExactly(CrdCleanupComponent.class);
        assertThat(new KeycloakComponent().requires()).isEmpty();
        assertThat(new KeycloakComponent().after()).isEmpty();
    }

    @Test
    void componentsAreNewForEachCall() {
        StackDefinition definition = StackDefinitions.get(new StackId(AWS, "full"));
        assertThat(definition.componentsFor(properties).get(0)).isNotSameAs(definition.componentsFor(properties).get(0));
    }

    @Test
    void cannotComposeAComponentWithoutWhatItRequires() {
        assertThatThrownBy(() -> StackDefinition.validate(STACK, List.of(new SparkComponent())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Stack aws-test needs SparkOperatorComponent before SparkComponent");
        assertThatThrownBy(() -> StackDefinition.validate(STACK, List.of(new MonitoringComponent())))
            .hasMessage("Stack aws-test needs CrdCleanupComponent before MonitoringComponent");
    }

    @Test
    void cannotComposeAComponentBeforeWhatItRequires() {
        assertThatThrownBy(() -> StackDefinition.validate(STACK, List.of(new SparkComponent(), new SparkOperatorComponent())))
            .hasMessage("Stack aws-test needs SparkOperatorComponent before SparkComponent");
    }

    @Test
    void cannotComposeAComponentBeforeWhatItMustComeAfter() {
        assertThatThrownBy(() -> StackDefinition.validate(STACK, List.of(new PolarisComponent(), new KeycloakComponent())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Stack aws-test needs KeycloakComponent before PolarisComponent");
    }

    @Test
    void canComposeAComponentWithoutWhatItMustComeAfterIfTheStackHasNoSuchComponent() {
        assertThatCode(() -> StackDefinition.validate(STACK, List.of(new PolarisComponent(), new JupyterComponent())))
            .doesNotThrowAnyException();
        assertThatCode(() -> StackDefinition.validate(STACK, List.of(new KeycloakComponent(), new PolarisComponent())))
            .doesNotThrowAnyException();
    }

    @Test
    void cannotComposeTheSameComponentTwice() {
        assertThatThrownBy(() -> StackDefinition.validate(STACK, List.of(new PolarisComponent(), new PolarisComponent())))
            .hasMessage("Stack aws-test has PolarisComponent more than once");
    }

    @Test
    void aStackIsRejectedWhenItIsBuiltNotOnlyWhenItIsDeployed() {
        Supplier<StackDefinition> wrong = () -> new StackDefinition(
            STACK, true, props -> List.of(new SparkComponent()));
        assertThatThrownBy(() -> wrong.get().componentsFor(properties))
            .hasMessage("Stack aws-test needs SparkOperatorComponent before SparkComponent");
    }
}
