package com.rdss.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ca.uhn.hl7v2.model.v25.datatype.CX;
import ca.uhn.hl7v2.model.v25.segment.PID;

import com.rdss.patients.Patient;
import com.rdss.patients.PatientRepository;
import com.rdss.system.SettingsService;

import org.springframework.stereotype.Component;

/**
 * Maps PID-3 identifiers to RDSS patients. An identifier whose assigning authority equals the
 * configured RDSS authority (default "RDSS") is an RDSS MRN; any other authority is looked up in
 * {@code patient_identifier}. Identifiers without an assigning authority are not trusted.
 */
@Component
public class PatientResolver {

    public record ExternalId(String system, String value) {
    }

    private final PatientRepository patients;
    private final PatientIdentifierRepository identifiers;
    private final SettingsService settings;

    public PatientResolver(PatientRepository patients, PatientIdentifierRepository identifiers,
                           SettingsService settings) {
        this.patients = patients;
        this.identifiers = identifiers;
        this.settings = settings;
    }

    public List<ExternalId> identifiers(PID pid) {
        List<ExternalId> out = new ArrayList<>();
        for (CX cx : pid.getPatientIdentifierList()) {
            String value = cx.getIDNumber().getValue();
            String system = cx.getAssigningAuthority().getNamespaceID().getValue();
            if (value != null && !value.isBlank() && system != null && !system.isBlank()) {
                out.add(new ExternalId(system.trim(), value.trim()));
            }
        }
        return out;
    }

    public String rdssAuthority() {
        return settings.get("hl7.rdss_assigning_authority", "RDSS");
    }

    public Optional<Patient> resolve(List<ExternalId> ids) {
        String rdss = rdssAuthority();
        for (ExternalId id : ids) {
            Optional<Patient> p = id.system().equals(rdss)
                    ? patients.findByMrn(id.value())
                    : identifiers.findBySystemAndValue(id.system(), id.value()).flatMap(i -> patients.findById(i.getPatientId()));
            if (p.isPresent()) {
                return p;
            }
        }
        return Optional.empty();
    }

    public Patient require(PID pid) {
        List<ExternalId> ids = identifiers(pid);
        if (ids.isEmpty()) {
            throw new Hl7ProcessingException("PID-3 must contain at least one identifier with an assigning authority");
        }
        return resolve(ids).orElseThrow(() -> new Hl7ProcessingException("Unknown patient identifier " + ids.get(0).system()
                + "/" + ids.get(0).value()));
    }

    /** Records external identifiers not yet mapped (never re-points an existing mapping). */
    public void link(Patient p, List<ExternalId> ids) {
        String rdss = rdssAuthority();
        for (ExternalId id : ids) {
            if (id.system().equals(rdss)) {
                continue;
            }
            var existing = identifiers.findBySystemAndValue(id.system(), id.value());
            if (existing.isEmpty()) {
                identifiers.save(new PatientIdentifier(p.getId(), id.system(), id.value()));
            } else if (!existing.get().getPatientId().equals(p.getId())) {
                throw new Hl7ProcessingException("Identifier " + id.system() + "/" + id.value()
                        + " is already mapped to a different patient");
            }
        }
    }
}
