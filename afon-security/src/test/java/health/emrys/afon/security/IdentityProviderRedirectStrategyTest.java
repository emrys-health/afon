package health.emrys.afon.security;

import health.emrys.afon.security.IdentityProvider.RedirectStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A redirect strategy is replace unless merge is asked for, and a value that is neither is refused, not taken for replace. */
class IdentityProviderRedirectStrategyTest {

    @Test
    void blankOrMissingIsReplace() {
        assertThat(RedirectStrategy.parse(null)).isEqualTo(RedirectStrategy.REPLACE);
        assertThat(RedirectStrategy.parse("")).isEqualTo(RedirectStrategy.REPLACE);
        assertThat(RedirectStrategy.parse("  ")).isEqualTo(RedirectStrategy.REPLACE);
    }

    @Test
    void replaceAndMergeAreKnownInAnyCaseAndWithSpaces() {
        assertThat(RedirectStrategy.parse("replace")).isEqualTo(RedirectStrategy.REPLACE);
        assertThat(RedirectStrategy.parse("Replace")).isEqualTo(RedirectStrategy.REPLACE);
        assertThat(RedirectStrategy.parse("merge")).isEqualTo(RedirectStrategy.MERGE);
        assertThat(RedirectStrategy.parse(" MERGE ")).isEqualTo(RedirectStrategy.MERGE);
    }

    @Test
    void anythingElseIsRefusedInsteadOfReplacing() {
        // eg a boolean, as the setting was first thought of: it must not quietly replace what it was set to keep
        assertThatThrownBy(() -> RedirectStrategy.parse("true"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A redirect strategy should be 'replace' or 'merge', not 'true'");
    }
}
