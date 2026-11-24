package health.emrys.afon.spark.guard;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.text.ParseException;
import java.util.Set;

/**
 * Checks an OIDC access token of the realm, as Trino and Polaris do: signed with one of the realm's keys, which are read from
 * its JWKS endpoint and cached, by RS256 alone, which is what KeyCloak signs with, so that neither {@code none} nor a key of
 * another kind is taken; issued by the realm; and not expired, with a minute's leeway for the clocks.
 */
public final class TokenVerifier {

    /** How long a call to the realm's JWKS endpoint may take, which the first call of a client waits for, or one with a new key. */
    private static final int KEYS_TIMEOUT_MILLIS = 5_000;

    /** A token that is missing, malformed, not the realm's, or expired. */
    public static final class BadTokenException extends Exception {
        BadTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    /**
     * @param issuer  the realm's URL, as its tokens name it, eg {@code https://<oidc domain>/realms/polaris}
     * @param keysUrl the realm's JWKS endpoint
     */
    public TokenVerifier(String issuer, URL keysUrl) {
        this(issuer, JWKSourceBuilder.create(keysUrl, new DefaultResourceRetriever(KEYS_TIMEOUT_MILLIS, KEYS_TIMEOUT_MILLIS))
                                     .retrying(true)
                                     .build());
    }

    TokenVerifier(String issuer, JWKSource<SecurityContext> keys) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("The issuer of the tokens is not given");
        }
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256), keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(new JWTClaimsSet.Builder().issuer(issuer).build(),
                                                                         Set.of("sub", "exp")));
    }

    /**
     * @return the subject of the token, the user's immutable id in the realm
     */
    public String subject(String token) throws BadTokenException {
        if (token == null || token.isBlank()) {
            throw new BadTokenException("no token", null);
        }
        try {
            String subject = processor.process(token, null).getSubject();
            if (subject == null || subject.isBlank()) {
                throw new BadTokenException("the token names no subject", null);
            }
            return subject;
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new BadTokenException(e.getMessage(), e);
        }
    }
}
