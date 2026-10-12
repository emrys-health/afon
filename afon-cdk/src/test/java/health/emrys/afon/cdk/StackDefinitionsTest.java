package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.cdk.components.SnowflakeComponent;
import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.cdk.components.TrinoComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackDefinitionsTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    private List<Class<?>> componentsOf(String stackId) {
        return StackDefinitions.get(StackId.parse(stackId)).components().apply(properties)
                               .stream().<Class<?>>map(StackComponent::getClass).toList();
    }

    @Test
    void stacksExtendOneAnotherByComponents() {
        assertThat(componentsOf("aws-keycloak")).containsExactly(KeycloakComponent.class);
        assertThat(componentsOf("aws-polaris")).containsExactly(PolarisComponent.class);
        assertThat(componentsOf("aws-spark")).containsAll(componentsOf("aws-jupyter")).contains(SparkComponent.class);
        assertThat(componentsOf("aws-trino")).containsAll(componentsOf("aws-spark")).contains(TrinoComponent.class);
    }

    @Test
    void snowflakeStackIsTheFullStackQueriedBySnowflake() {
        assertThat(componentsOf("aws-snowflake")).startsWith(componentsOf("aws-full").toArray(new Class<?>[0]))
            .hasSize(componentsOf("aws-full").size() + 1).endsWith(SnowflakeComponent.class);
    }

    @Test
    void awsStacksDeployOwnKeyCloakUnlessExternal() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(componentsOf("aws-full")).contains(KeycloakComponent.class);
        assertThat(componentsOf("aws-full").indexOf(KeycloakComponent.class))
            .isLessThan(componentsOf("aws-full").indexOf(PolarisComponent.class));
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("aws-full")).doesNotContain(KeycloakComponent.class);
    }

    @Test
    void componentsAreNewForEachStackBuilt() {
        StackDefinition definition = StackDefinitions.get(StackId.parse("azure-full"));
        assertThat(definition.components().apply(properties).get(0))
            .isNotSameAs(definition.components().apply(properties).get(0));
        assertThat(componentsOf("azure-full")).containsExactlyInAnyOrderElementsOf(componentsOf("aws-full"));
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("azure-full")).contains(KeycloakComponent.class, JupyterComponent.class);
    }

    @Test
    void onlyKeycloakStackDoesNotPinEgressIp() {
        StackDefinitions.all().forEach(definition ->
            assertThat(definition.pinnedEgressIp()).as(definition.id().toString())
                                                   .isEqualTo(!definition.id().equals(new StackId(AWS, "keycloak"))));
        assertThat(StackDefinitions.find(new StackId(AZURE, "full"))).isPresent();
    }

    @Test
    void cannotGetUnknownStack() {
        assertThatThrownBy(() -> StackDefinitions.get(StackId.parse("gcp-full")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("No such stack: gcp-full");
    }
}
