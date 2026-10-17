package com.rdss.hl7;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** HL7 transport configuration ({@code rdss.hl7.*}); runtime behaviour lives in system settings. */
@ConfigurationProperties(prefix = "rdss.hl7")
public record Hl7Properties(String inboundApiKey, Mllp mllp, Duration retryInterval) {

    public record Mllp(boolean enabled, int port) {
    }

    public boolean httpInboundEnabled() {
        return inboundApiKey != null && !inboundApiKey.isBlank();
    }
}
