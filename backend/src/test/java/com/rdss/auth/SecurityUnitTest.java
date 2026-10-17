package com.rdss.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.rdss.common.security.Role;
import com.rdss.config.RdssProperties;
import com.rdss.users.AppUser;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityUnitTest {

    private static RdssProperties props(String secret) {
        return new RdssProperties(new RdssProperties.Security(new RdssProperties.Jwt(secret, Duration.ofMinutes(5), "rdss")),
                new RdssProperties.Cors(List.of()), null, null, null);
    }

    private static AppUser user() {
        AppUser u = new AppUser("u1", "u1@test", "hash", "User One", Role.CLINICIAN);
        ReflectionTestUtils.setField(u, "id", 42L);
        return u;
    }

    @Test
    void jwtRoundTripAndExpiry() {
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        var issuer = new JwtService(props("0123456789abcdef0123456789abcdef"), Clock.fixed(t0, ZoneOffset.UTC));
        String token = issuer.issue(user()).token();

        assertThat(issuer.validate(token)).contains(42L);
        var later = new JwtService(props("0123456789abcdef0123456789abcdef"),
                Clock.fixed(t0.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        assertThat(later.validate(token)).isEmpty();
    }

    @Test
    void tokenSignedWithOtherSecretIsRejected() {
        String token = new JwtService(props("0123456789abcdef0123456789abcdef")).issue(user()).token();
        assertThat(new JwtService(props("ffffffffffffffffffffffffffffffff")).validate(token)).isEmpty();
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService(props("too-short"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(props(null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lockoutExpiresAfterLockDuration() {
        var clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        var attempts = new LoginAttemptService(clock);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            assertThat(attempts.isLocked("Bob")).isFalse();
            attempts.recordFailure("bob");
        }
        assertThat(attempts.isLocked("BOB")).isTrue();
        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(1));
        assertThat(attempts.isLocked("bob")).isFalse();
        // A failure after expiry starts a fresh count rather than re-locking immediately.
        attempts.recordFailure("bob");
        assertThat(attempts.isLocked("bob")).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
