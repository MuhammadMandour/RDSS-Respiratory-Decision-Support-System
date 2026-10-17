package com.rdss.hl7.handlers;

import java.util.Set;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.hl7.Hl7MessageHandler;
import com.rdss.hl7.Hl7ProcessingException;
import com.rdss.hl7.PatientResolver;
import com.rdss.imaging.model.ImagingOrder;
import com.rdss.imaging.model.ImagingOrderRepository;
import com.rdss.patients.Patient;

import org.springframework.stereotype.Component;

/**
 * ORM^O01 imaging orders:
 * <ul>
 *   <li>ORC-1 = NW: create an imaging order. ORC-2 placer order number becomes the external id
 *       (a repeated NW for the same placer number is idempotent); OBR-4 procedure code/text,
 *       OBR-24 diagnostic service section as modality (CT, DX, CR, MR, US, PT, NM; else OT),
 *       OBR-13 relevant clinical information as indication.</li>
 *   <li>ORC-1 = CA: cancel the order with that placer number.</li>
 * </ul>
 * Other order control codes are rejected with AE.
 */
@Component
public class OrmHandler implements Hl7MessageHandler {

    private static final Set<String> MODALITIES = Set.of("CT", "DX", "CR", "MR", "US", "PT", "NM");

    private final PatientResolver resolver;
    private final ImagingOrderRepository orders;

    public OrmHandler(PatientResolver resolver, ImagingOrderRepository orders) {
        this.resolver = resolver;
        this.orders = orders;
    }

    @Override
    public Set<String> messageTypes() {
        return Set.of("ORM^O01");
    }

    @Override
    public Result handle(Message message, Terser t) throws HL7Exception {
        Patient patient = resolver.require((PID) t.getSegment("/.PID"));
        String control = t.get("/.ORC-1");
        String placer = t.get("/.ORC-2-1");
        if (placer == null || placer.isBlank()) {
            throw new Hl7ProcessingException("ORC-2 placer order number is required");
        }
        final String placerId = placer.trim();
        var existing = orders.findByExternalOrderId(placerId);

        if ("CA".equals(control)) {
            ImagingOrder o = existing.orElseThrow(() -> new Hl7ProcessingException("No order with placer number " + placerId));
            if (!o.getPatientId().equals(patient.getId())) {
                throw new Hl7ProcessingException("Placer order belongs to a different patient");
            }
            o.cancel();
            return new Result(patient.getId(), "ImagingOrder", String.valueOf(o.getId()), "order cancelled");
        }
        if (!"NW".equals(control)) {
            throw new Hl7ProcessingException("Unsupported order control code ORC-1=" + control + " (supported: NW, CA)");
        }
        if (existing.isPresent()) {
            if (!existing.get().getPatientId().equals(patient.getId())) {
                throw new Hl7ProcessingException("Placer order number is already used for a different patient");
            }
            return new Result(patient.getId(), "ImagingOrder", String.valueOf(existing.get().getId()), "order already exists");
        }
        String code = t.get("/.OBR-4-1");
        String text = t.get("/.OBR-4-2");
        if ((code == null || code.isBlank()) && (text == null || text.isBlank())) {
            throw new Hl7ProcessingException("OBR-4 universal service identifier is required");
        }
        String section = t.get("/.OBR-24");
        String modality = section != null && MODALITIES.contains(section.trim().toUpperCase()) ? section.trim().toUpperCase() : "OT";
        String procedure = (text != null && !text.isBlank() ? text.trim() : code.trim());
        if (procedure.length() > 255) {
            procedure = procedure.substring(0, 255);
        }
        String indication = t.get("/.OBR-13");
        ImagingOrder o = new ImagingOrder(patient.getId(), null, "RDSS" + orders.nextAccessionNumber(), modality,
                "CHEST", procedure, indication == null ? null : indication.trim(), ImagingOrder.Priority.ROUTINE, null);
        o.setExternalOrderId(placerId);
        o = orders.save(o);
        return new Result(patient.getId(), "ImagingOrder", String.valueOf(o.getId()), "order created");
    }
}
