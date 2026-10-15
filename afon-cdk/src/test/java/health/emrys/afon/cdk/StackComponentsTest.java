package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.PolarisComponent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackComponentsTest {

    @Test
    void createsANewComponentOfTheNameEachTime() {
        StackComponent first = StackComponents.find("Polaris").orElseThrow().get();
        assertThat(first).isInstanceOf(PolarisComponent.class);
        assertThat(StackComponents.find("Polaris").orElseThrow().get()).isNotSameAs(first);
    }

    @Test
    void hasNoComponentOfAnotherName() {
        assertThat(StackComponents.find("Nonesuch")).isEmpty();
        assertThat(StackComponents.find("polaris")).isEmpty();
        assertThat(StackComponents.names()).contains("Polaris", "Keycloak", "Snowflake").doesNotContain("Nonesuch");
    }

    @Test
    void namesCannotBeChangedByTheCaller() {
        assertThatThrownBy(() -> StackComponents.names().add("Mine")).isInstanceOf(UnsupportedOperationException.class);
    }
}
