package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidateStackMainTest {

    @Test
    void noArgsGiveNoArguments() {
        assertThat(ValidateStackMain.Arguments.parse(new String[0])).isNull();
    }

    @Test
    void runsDefaultToThree() {
        assertThat(ValidateStackMain.Arguments.parse(new String[]{"aws-full"}))
            .isEqualTo(new ValidateStackMain.Arguments("aws-full", 3));
    }

    @Test
    void runsAreGivenByOption() {
        assertThat(ValidateStackMain.Arguments.parse(new String[]{"aws-full", "--runs", "5"}))
            .isEqualTo(new ValidateStackMain.Arguments("aws-full", 5));
    }

    @Test
    void runsMustBeAtLeastOne() {
        assertThatThrownBy(() -> ValidateStackMain.Arguments.parse(new String[]{"aws-full", "--runs", "0"}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> ValidateStackMain.Arguments.parse(new String[]{"aws-full", "--runs", "many"}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("many");
    }

    @Test
    void otherArgsAreRejected() {
        assertThatThrownBy(() -> ValidateStackMain.Arguments.parse(new String[]{"aws-full", "--run", "2"}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage");
        assertThatThrownBy(() -> ValidateStackMain.Arguments.parse(new String[]{"aws-full", "extra"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
