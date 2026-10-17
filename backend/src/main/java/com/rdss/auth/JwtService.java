package com.rdss.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import com.rdss.config.RdssProperties;
import com.rdss.users.AppUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Issues and validates HS256 access tokens. Tokens carry identifiers only, never PHI. */
@Service
public class JwtService {

    static final int MIN_SECRET_BYTES = 32;

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final SecretKey key;
    private final Duration expiration;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwtService(RdssProperties props) {
        this(props, Clock.systemUTC());
    }

    JwtService(RdssProperties props, Clock clock) {
        RdssProperties.Jwt cfg = props.security().jwt();
        String secret = cfg.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = cfg.expiration() == null ? Duration.ofHours(8) : cfg.expiration();
        this.issuer = cfg.issuer() == null ? "rdss" : cfg.issuer();
        this.clock = clock;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = clock.instant();
        Instant exp = now.plus(expiration);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, exp);
    }

    /** Returns the user id if the token is authentic, unexpired and from this issuer. */
    public Optional<Long> validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
