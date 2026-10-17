package com.rdss.imaging.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.rdss.auth.JwtService;
import com.rdss.config.RdssProperties;

import org.junit.jupiter.api.Test;

class ViewerSecurityTest {

    static final Set<String> GRANTED = Set.of("1.2.3", "1.2.4");

    static java.util.Optional<DicomWebAccessPolicy.Decision> authz(String path, String... kv) {
        java.util.Map<String, String[]> params = new java.util.LinkedHashMap<>();
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], new String[] {kv[i + 1]});
            q.append(q.isEmpty() ? "" : "&").append(kv[i]).append('=').append(kv[i + 1]);
        }
        return DicomWebAccessPolicy.authorize(path, params, q.isEmpty() ? null : q.toString(), GRANTED);
    }

    @Test
    void allowsOnlyGrantedStudyPaths() {
        assertThat(authz("/studies/1.2.3/series")).hasValueSatisfying(d -> assertThat(d.uids()).containsExactly("1.2.3"));
        assertThat(authz("/studies/1.2.3/series/9.9/instances/8.8/frames/1")).isPresent();
        assertThat(authz("/studies/1.2.3/metadata")).isPresent();
        assertThat(authz("/studies/9.9.9/series")).isEmpty();
    }

    @Test
    void qidoWithUidFilterMustOnlyNameGrantedStudies() {
        assertThat(authz("/studies", "StudyInstanceUID", "1.2.3")).isPresent();
        assertThat(authz("/studies", "StudyInstanceUID", "1.2.3,1.2.4")).isPresent();
        assertThat(authz("/studies", "StudyInstanceUID", "1.2.3\\1.2.4")).isPresent();
        assertThat(authz("/studies", "StudyInstanceUID", "1.2.3,6.6.6")).isEmpty();
        assertThat(authz("/studies", "StudyInstanceUID", "*")).isEmpty();
    }

    @Test
    void unfilteredQidoIsNarrowedToTheGrant() {
        var d = authz("/studies", "00100020", "MRN100002", "limit", "101");
        assertThat(d).isPresent();
        assertThat(d.get().narrowedQuery()).contains("00100020=MRN100002&limit=101&StudyInstanceUID=1.2.3,1.2.4");
        assertThat(authz("/studies").get().narrowedQuery()).contains("StudyInstanceUID=1.2.3,1.2.4");
    }

    @Test
    void refusesTagAliasOtherRootsAndPathTricks() {
        // The numeric alias could otherwise be combined with the narrowing filter.
        assertThat(authz("/studies", "0020000D", "6.6.6")).isEmpty();
        assertThat(authz("/studies", "0020000d", "1.2.3")).isEmpty();
        assertThat(authz("/series")).isEmpty();
        assertThat(authz("/instances")).isEmpty();
        assertThat(authz("/studies/1.2.3/../9.9.9")).isEmpty();
        assertThat(authz("/studies/1.2.3//x")).isEmpty();
        assertThat(authz("/studies/1.2.3%2F..")).isEmpty();
        assertThat(DicomWebAccessPolicy.authorize(null, java.util.Map.of(), null, GRANTED)).isEmpty();
        assertThat(DicomWebAccessPolicy.authorize("/studies", java.util.Map.of(), null, Set.of())).isEmpty();
    }

    static RdssProperties props(String secret) {
        return new RdssProperties(new RdssProperties.Security(new RdssProperties.Jwt(secret, Duration.ofHours(1), "rdss")),
                null, null, null, null);
    }

    @Test
    void viewerTokenRoundTripExpiryAndSeparationFromApiTokens() {
        String secret = "0123456789abcdef0123456789abcdef";
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        var svc = new ViewerTokenService(props(secret), Clock.fixed(t0, ZoneOffset.UTC));
        var issued = svc.issue(42L, List.of("1.2.3"));

        assertThat(svc.validate(issued.token())).hasValueSatisfying(g -> {
            assertThat(g.userId()).isEqualTo(42L);
            assertThat(g.studyInstanceUids()).containsExactly("1.2.3");
        });
        var later = new ViewerTokenService(props(secret), Clock.fixed(t0.plus(ViewerTokenService.TTL).plusSeconds(1), ZoneOffset.UTC));
        assertThat(later.validate(issued.token())).isEmpty();
        // A viewer grant is not an API bearer token.
        assertThat(new JwtService(props(secret)).validate(issued.token())).isEmpty();
        assertThat(svc.validate("garbage")).isEmpty();
    }
}
