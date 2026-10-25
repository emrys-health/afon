package health.emrys.afon.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AfonCliApplicationTest {

    @Test
    void userMessageIsThatOfTheFailure() {
        assertThat(AfonCliApplication.userMessage(new IllegalStateException("Run with --replace"))).isEqualTo("Run with --replace");
    }

    @Test
    void userMessageSkipsWrappersOfTheirCause() {
        Exception wrapped = new RuntimeException(new IllegalStateException("Run with --replace"));
        assertThat(AfonCliApplication.userMessage(wrapped)).isEqualTo("Run with --replace");
        Exception wrappedWithText = new RuntimeException(new IllegalStateException("Run with --replace").toString(),
                                                         new IllegalStateException("Run with --replace"));
        assertThat(AfonCliApplication.userMessage(wrappedWithText)).isEqualTo("Run with --replace");
    }

    @Test
    void userMessageKeepsAWrapperWithItsOwnMessage() {
        Exception failure = new RuntimeException("Step failed", new IllegalStateException("detail"));
        assertThat(AfonCliApplication.userMessage(failure)).isEqualTo("Step failed");
    }

    @Test
    void userMessageFallsBackToTheClassName() {
        assertThat(AfonCliApplication.userMessage(new NullPointerException())).isEqualTo("NullPointerException");
    }
}
