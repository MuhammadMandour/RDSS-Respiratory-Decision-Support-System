package com.rdss.hl7;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries transient inbound failures and undelivered outbound messages whose backoff has elapsed. */
@Component
public class Hl7RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(Hl7RetryScheduler.class);

    private final Hl7MessageRepository messages;
    private final Hl7InboundService inbound;
    private final Hl7OutboundService outbound;

    public Hl7RetryScheduler(Hl7MessageRepository messages, Hl7InboundService inbound, Hl7OutboundService outbound) {
        this.messages = messages;
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Scheduled(fixedDelayString = "${rdss.hl7.retry-interval:PT1M}", initialDelayString = "PT30S")
    public void retryDue() {
        List<Hl7Message> due = messages.findDueForRetry(
                List.of(Hl7Message.Status.FAILED, Hl7Message.Status.PENDING), Instant.now(), PageRequest.of(0, 50));
        for (Hl7Message m : due) {
            try {
                if (m.getDirection() == Hl7Message.Direction.INBOUND) {
                    inbound.process(m.getId());
                } else if (outbound.enabled()) {
                    outbound.send(m.getId());
                }
            } catch (RuntimeException ex) {
                log.error("Retry of HL7 message #{} failed", m.getId(), ex);
            }
        }
    }
}
