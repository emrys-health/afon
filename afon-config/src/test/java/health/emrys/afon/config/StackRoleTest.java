package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackRoleTest {

    @Test
    void valuesAreInLowerCaseAndFoundByThem() {
        assertThat(StackRole.values()).extracting(StackRole::value).containsExactly("catalog", "identity", "tre");
        for (StackRole role : StackRole.values()) {
            assertThat(role.value()).isEqualTo(role.name().toLowerCase()).isEqualTo(role.toString());
            assertThat(StackRole.of(role.value())).contains(role);
        }
    }

    @Test
    void otherValuesAreNoRole() {
        assertThat(StackRole.of("snowflake")).isEmpty();
        assertThat(StackRole.of("Tre")).isEmpty();
        assertThat(StackRole.of("")).isEmpty();
    }
}
