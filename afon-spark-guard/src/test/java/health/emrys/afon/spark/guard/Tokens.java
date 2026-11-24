package health.emrys.afon.spark.guard;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** A realm of its own, to sign tokens with, and a verifier of its tokens. */
final class Tokens {

    static final String ISSUER  = "https://oidc.example/realms/polaris";
    static final String SUBJECT = "8f0c2a5e-1d2b-4c3d-9e8f-0a1b2c3d4e5f";

    final RSAKey key   = generate("realm");
    final RSAKey other = generate("other");

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    TokenVerifier verifier() {
        return new TokenVerifier(ISSUER, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
    }

    JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUBJECT).issueTime(Date.from(now))
                                         .expirationTime(Date.from(now.plus(Duration.ofHours(1))));
    }

    String token() {
        return signed(claims().build(), key);
    }

    String signed(JWTClaimsSet claims, RSAKey with) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(with));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signed with a shared secret, by HS256, which a verifier that trusted the header's algorithm might check with the public key. */
    String hmacSigned() {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(), claims().build());
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    String unsigned() {
        return new PlainJWT(claims().build()).serialize();
    }
}
