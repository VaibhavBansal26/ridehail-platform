package com.ridehail.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 JWTs. Access tokens (short-lived) authenticate API calls and the
 * WebSocket CONNECT; refresh tokens (long-lived) can only be exchanged at /api/auth/refresh.
 * The "typ" claim keeps the two from being used in each other's place.
 */
@Service
public class JwtService {
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    private final SecurityProperties props;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;

    JwtService(SecurityProperties props, Clock clock) {
        byte[] secret = props.jwtSecret() == null ? new byte[0] : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("ridehail.security.jwt-secret must be at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.props = props;
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, ACCESS);
        this.refreshDecoder = decoder(key, REFRESH);
    }

    public JwtDecoder accessDecoder() { return accessDecoder; }

    public TokenPair issue(UserAccount account) {
        Instant now = Instant.now(clock);
        return new TokenPair(
                sign(account, ACCESS, now, Duration.ofMinutes(props.accessTtlMinutes())),
                sign(account, REFRESH, now, Duration.ofDays(props.refreshTtlDays())));
    }

    /** @throws JwtException if the token is invalid, expired, or not a refresh token */
    public UUID accountIdFromRefresh(String refreshToken) {
        return UUID.fromString(refreshDecoder.decode(refreshToken).getSubject());
    }

    private String sign(UserAccount a, String type, Instant now, Duration ttl) {
        var claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject(a.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("role", a.getRole().name())
                .claim("pid", a.getProfileId().toString())
                .claim("typ", type)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static JwtDecoder decoder(SecretKey key, String expectedType) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> type = jwt -> expectedType.equals(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong token type", null));
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), type));
        return d;
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
