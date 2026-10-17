package com.rdss.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed application configuration bound from the {@code rdss.*} namespace.
 * Every value can be overridden with an environment variable (see application.yml).
 */
@ConfigurationProperties(prefix = "rdss")
public record RdssProperties(Security security, Cors cors, Orthanc orthanc, Seed seed, Bootstrap bootstrap) {

    public record Security(Jwt jwt) {
    }

    public record Jwt(String secret, Duration expiration, String issuer) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Orthanc(String url, String username, String password,
                          Duration connectTimeout, Duration readTimeout) {
    }

    /** Synthetic demo data. {@code demoPassword} is the shared password of all demo accounts. */
    public record Seed(boolean demoData, String demoPassword) {
    }

    /** Creates the first administrator on an empty database (production-style first start). */
    public record Bootstrap(String adminUsername, String adminEmail, String adminPassword) {
    }
}
