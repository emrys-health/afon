package health.emrys.afon.cdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelisterTest {

    @Test
    void cidrsAreParsedFromTheCsv() {
        assertThat(Whitelister.parseCidrsIn(" a/32 ,, b/24,")).containsExactly("a/32", "b/24");
        assertThat(Whitelister.parseCidrsIn("")).isEmpty();
    }

}