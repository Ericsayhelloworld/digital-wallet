package com.example.notification.acceptance;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Signs RS256 tokens like auth-service does (AUTH-3); the public key is served as JWKS by FakeBank. */
final class TestTokens {

    static final String ISSUER = "test-issuer";

    private final RSAKey key;
    private final JwtEncoder encoder;

    TestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    String mint(UUID userId, String username, String name, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("username", username)
                .claim("name", name)
                .claim("roles", roles)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
