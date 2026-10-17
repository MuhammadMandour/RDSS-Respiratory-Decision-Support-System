package com.rdss.imaging.viewer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKey;

import com.rdss.config.RdssProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Short-lived, study-scoped grants for the DICOM viewer. The grant is delivered as an HttpOnly
 * cookie restricted to the DICOMweb proxy path, so the viewer (which cannot attach our bearer
 * token) can load exactly the studies the user was authorized for, and nothing else.
 */
@Service
public class ViewerTokenService {

    public static final String COOKIE = "RDSS_VIEWER";
    public static final String COOKIE_PATH = "/api/v1/dicomweb";
    static final String AUDIENCE = "rdss-dicomweb";
    static final Duration TTL = Duration.ofMinutes(30);

    public record Grant(Long userId, Set<String> studyInstanceUids) {
    }

    public record IssuedGrant(String token, Instant expiresAt) {
    }

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public ViewerTokenService(RdssProperties props) {
        this(props, Clock.systemUTC());
    }

    ViewerTokenService(RdssProperties props, Clock clock) {
        // Derived from the JWT secret but bound to a different audience, so a viewer cookie can
        // never be used as an API bearer token (the API filter requires the "rdss" issuer and no audience).
        this.key = Keys.hmacShaKeyFor((props.security().jwt().secret() + ":viewer").getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public IssuedGrant issue(Long userId, Collection<String> studyInstanceUids) {
        Instant now = clock.instant();
        Instant exp = now.plus(TTL);
        String token = Jwts.builder()
                .audience().add(AUDIENCE).and()
                .subject(String.valueOf(userId))
                .claim("studies", List.copyOf(studyInstanceUids))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedGrant(token, exp);
    }

    public Optional<Grant> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims c = Jwts.parser().verifyWith(key).requireAudience(AUDIENCE)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            List<?> studies = c.get("studies", List.class);
            Set<String> uids = studies == null ? Set.of()
                    : Set.copyOf(studies.stream().map(String::valueOf).toList());
            return Optional.of(new Grant(Long.valueOf(c.getSubject()), uids));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
