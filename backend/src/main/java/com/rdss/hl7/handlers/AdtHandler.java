package com.rdss.hl7.handlers;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.datatype.XAD;
import ca.uhn.hl7v2.model.v25.datatype.XPN;
import ca.uhn.hl7v2.model.v25.datatype.XTN;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.hl7.Hl7MessageHandler;
import com.rdss.hl7.Hl7ProcessingException;
import com.rdss.hl7.Hl7Support;
import com.rdss.hl7.PatientResolver;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientRepository;

import org.springframework.stereotype.Component;

/**
 * ADT profile:
 * <ul>
 *   <li>A01 (admit), A04 (register), A08 (update): create or update the patient's demographics
 *       from PID and map its external identifiers. Clinical data is never changed by ADT.</li>
 *   <li>A03 (discharge): acknowledged and logged; RDSS has no inpatient bed/stay model.</li>
 * </ul>
 * Field mapping: PID-3 identifiers, PID-5 family/given name, PID-7 date of birth,
 * PID-8 sex (F/M/O/U), PID-11 street/city/postal code/country, PID-13 home phone.
 */
@Component
public class AdtHandler implements Hl7MessageHandler {

    private final PatientResolver resolver;
    private final PatientRepository patients;

    public AdtHandler(PatientResolver resolver, PatientRepository patients) {
        this.resolver = resolver;
        this.patients = patients;
    }

    @Override
    public Set<String> messageTypes() {
        return Set.of("ADT^A01", "ADT^A03", "ADT^A04", "ADT^A08");
    }

    @Override
    public Result handle(Message message, Terser t) throws HL7Exception {
        PID pid = (PID) t.getSegment("/.PID");
        List<PatientResolver.ExternalId> ids = resolver.identifiers(pid);
        if (ids.isEmpty()) {
            throw new Hl7ProcessingException("PID-3 must contain at least one identifier with an assigning authority");
        }
        String trigger = t.get("/MSH-9-2");
        if ("A03".equals(trigger)) {
            Patient p = resolver.require(pid);
            return new Result(p.getId(), "Patient", String.valueOf(p.getId()), "discharge acknowledged; no state change");
        }

        XPN name = pid.getPatientName(0);
        String family = name.getFamilyName().getSurname().getValue();
        String given = name.getGivenName().getValue();
        LocalDate dob = Hl7Support.parseDate(pid.getDateTimeOfBirth().getTime().getValue());
        if (family == null || given == null || dob == null) {
            throw new Hl7ProcessingException("PID-5 (family and given name) and PID-7 (date of birth) are required");
        }
        if (dob.isAfter(LocalDate.now())) {
            throw new Hl7ProcessingException("PID-7 date of birth is in the future");
        }

        Patient p = resolver.resolve(ids).orElse(null);
        boolean created = p == null;
        if (created) {
            p = patients.save(new Patient("MRN" + patients.nextMrnNumber(), given.trim(), family.trim(), dob,
                    sex(pid.getAdministrativeSex().getValue())));
        } else {
            p.setFirstName(given.trim());
            p.setLastName(family.trim());
            p.setDateOfBirth(dob);
            p.setSex(sex(pid.getAdministrativeSex().getValue()));
        }
        if (pid.getPatientAddressReps() > 0) {
            XAD a = pid.getPatientAddress(0);
            p.setAddressLine(blankToNull(a.getStreetAddress().getStreetOrMailingAddress().getValue()));
            p.setCity(blankToNull(a.getCity().getValue()));
            p.setPostalCode(blankToNull(a.getZipOrPostalCode().getValue()));
            p.setCountry(blankToNull(a.getCountry().getValue()));
        }
        if (pid.getPhoneNumberHomeReps() > 0) {
            XTN phone = pid.getPhoneNumberHome(0);
            String number = phone.getTelephoneNumber().getValue();
            if (number != null && number.matches("^[0-9+()\\-. ]{5,50}$")) {
                p.setPhone(number.trim());
            }
        }
        p = patients.save(p);
        resolver.link(p, ids);
        return new Result(p.getId(), "Patient", String.valueOf(p.getId()), created ? "patient created" : "patient updated");
    }

    static Patient.Sex sex(String v) {
        if (v == null) {
            return Patient.Sex.UNKNOWN;
        }
        return switch (v.trim().toUpperCase()) {
            case "F" -> Patient.Sex.FEMALE;
            case "M" -> Patient.Sex.MALE;
            case "O", "A", "N" -> Patient.Sex.OTHER;
            default -> Patient.Sex.UNKNOWN;
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
