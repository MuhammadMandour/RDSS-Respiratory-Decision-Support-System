package com.rdss.hl7;

import java.util.Map;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.validation.impl.NoValidation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Optional MLLP listener ({@code HL7_MLLP_ENABLED=true}, port {@code HL7_MLLP_PORT}, default 2575).
 * Parsing at the socket is lenient; every message is handed, as text, to {@link Hl7InboundService},
 * which applies the same validation, logging, idempotency and ACK rules as the HTTP transport.
 * MLLP has no authentication of its own: expose it only on a trusted network segment.
 */
@Component
@ConditionalOnProperty(prefix = "rdss.hl7.mllp", name = "enabled", havingValue = "true")
public class Hl7MllpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Hl7MllpServer.class);

    private final Hl7InboundService inbound;
    private final int port;
    private HapiContext context;
    private HL7Service server;

    public Hl7MllpServer(Hl7InboundService inbound, Hl7Properties props) {
        this.inbound = inbound;
        this.port = props.mllp().port();
    }

    @Override
    public void start() {
        context = new DefaultHapiContext();
        context.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
        context.setValidationContext(new NoValidation());
        server = context.newServer(port, false);
        server.registerApplication("*", "*", new ReceivingApplication<Message>() {
            @Override
            public Message processMessage(Message message, Map<String, Object> metadata) throws HL7Exception {
                String ack = inbound.receive(message.encode(), Hl7Message.Transport.MLLP);
                return context.getPipeParser().parse(ack);
            }

            @Override
            public boolean canProcess(Message message) {
                return true;
            }
        });
        try {
            server.startAndWait();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the MLLP listener", ex);
        }
        log.info("HL7 MLLP listener started on port {}", port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stopAndWait();
        }
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && server.isRunning();
    }
}
