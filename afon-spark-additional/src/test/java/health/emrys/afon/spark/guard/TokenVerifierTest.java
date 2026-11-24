package health.emrys.afon.spark.guard;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenVerifierTest {

    private final Tokens        tokens   = new Tokens();
    private final TokenVerifier verifier = tokens.verifier();

    @Test
    void aTokenOfTheRealmIsOfItsSubject() throws Exception {
        assertThat(verifier.subject(tokens.token())).isEqualTo(Tokens.SUBJECT);
    }

    @Test
    void noTokenIsRefused() {
        assertThatThrownBy(() -> verifier.subject(null)).isInstanceOf(TokenVerifier.BadTokenException.class);
        assertThatThrownBy(() -> verifier.subject(" ")).isInstanceOf(TokenVerifier.BadTokenException.class);
        assertThatThrownBy(() -> verifier.subject("not.a.token")).isInstanceOf(TokenVerifier.BadTokenException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String forged = tokens.signed(tokens.claims().build(), tokens.other);

        assertThatThrownBy(() -> verifier.subject(forged)).isInstanceOf(TokenVerifier.BadTokenException.class);
    }

    @Test
    void aTokenSignedByAnotherAlgorithmOrNotAtAllIsRefused() {
        assertThatThrownBy(() -> verifier.subject(tokens.hmacSigned())).isInstanceOf(TokenVerifier.BadTokenException.class);
        assertThatThrownBy(() -> verifier.subject(tokens.unsigned())).isInstanceOf(TokenVerifier.BadTokenException.class);
    }

    @Test
    void aTokenOfAnotherIssuerIsRefused() {
        String token = tokens.signed(tokens.claims().issuer("https://oidc.example/realms/other").build(), tokens.key);

        assertThatThrownBy(() -> verifier.subject(token)).isInstanceOf(TokenVerifier.BadTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        Date   twoMinutesAgo = Date.from(Instant.now().minus(Duration.ofMinutes(2)));
        String token         = tokens.signed(tokens.claims().expirationTime(twoMinutesAgo).build(), tokens.key);

        assertThatThrownBy(() -> verifier.subject(token)).isInstanceOf(TokenVerifier.BadTokenException.class);
    }

    @Test
    void aTokenWithNoSubjectOrNoExpiryIsRefused() {
        String noSubject = tokens.signed(tokens.claims().subject(null).build(), tokens.key);
        String noExpiry  = tokens.signed(tokens.claims().expirationTime(null).build(), tokens.key);

        assertThatThrownBy(() -> verifier.subject(noSubject)).isInstanceOf(TokenVerifier.BadTokenException.class);
        assertThatThrownBy(() -> verifier.subject(noExpiry)).isInstanceOf(TokenVerifier.BadTokenException.class);
    }
}
