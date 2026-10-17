package com.rdss.hl7.handlers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.util.Terser;

import com.rdss.hl7.Hl7MessageHandler;
import com.rdss.hl7.Hl7ProcessingException;
import com.rdss.hl7.Hl7Support;
import com.rdss.hl7.PatientResolver;
import com.rdss.hl7.SystemAccount;
import com.rdss.patients.Patient;
import com.rdss.respiratory.Observation;
import com.rdss.respiratory.ObservationRepository;
import com.rdss.respiratory.PulmonaryFunctionTest;
import com.rdss.respiratory.PulmonaryFunctionTestRepository;
import com.rdss.system.SettingsService;

import org.springframework.stereotype.Component;

/**
 * ORU^R01 results. Numeric OBX values coded in LOINC (OBX-3 coding system "LN") are imported:
 * <pre>
 *   9279-1  Respiratory rate (/min)         8867-4  Heart rate (/min)
 *   59408-5 SpO2 by pulse oximetry (%)       2708-6  Oxygen saturation in arterial blood (%)
 *   8480-6  Systolic blood pressure (mm Hg)  8462-4  Diastolic blood pressure (mm Hg)
 *   8310-5  Body temperature (Cel; [degF] converted)
 *   20150-9 FEV1 (L)                         19870-5 FVC (L)
 * </pre>
 * Vitals from one OBR become one observation set; FEV1/FVC become one PFT. Other codes and
 * results with status D (deleted) or X (cannot obtain) are ignored and listed in the log note.
 * Values outside RDSS's accepted ranges are rejected (AE) rather than silently clipped.
 */
@Component
public class OruHandler implements Hl7MessageHandler {

    static final Set<String> VITALS = Set.of("9279-1", "8867-4", "59408-5", "2708-6", "8480-6", "8462-4", "8310-5");

    private final PatientResolver resolver;
    private final ObservationRepository observations;
    private final PulmonaryFunctionTestRepository pfts;
    private final SystemAccount system;
    private final SettingsService settings;

    public OruHandler(PatientResolver resolver, ObservationRepository observations,
                      PulmonaryFunctionTestRepository pfts, SystemAccount system, SettingsService settings) {
        this.resolver = resolver;
        this.observations = observations;
        this.pfts = pfts;
        this.system = system;
        this.settings = settings;
    }

    @Override
    public Set<String> messageTypes() {
        return Set.of("ORU^R01");
    }

    @Override
    public Result handle(Message message, Terser t) throws HL7Exception {
        if (!(message instanceof ORU_R01 oru)) {
            throw new Hl7ProcessingException("Unexpected ORU structure");
        }
        ZoneId zone = settings.clinicZone();
        String controlId = t.get("/MSH-10");
        List<String> created = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Patient patient = null;

        for (int p = 0; p < oru.getPATIENT_RESULTReps(); p++) {
            ORU_R01_PATIENT_RESULT pr = oru.getPATIENT_RESULT(p);
            patient = resolver.require(pr.getPATIENT().getPID());
            for (int o = 0; o < pr.getORDER_OBSERVATIONReps(); o++) {
                ORU_R01_ORDER_OBSERVATION oo = pr.getORDER_OBSERVATION(o);
                Instant obrTime = Hl7Support.parseInstant(oo.getOBR().getObservationDateTime().getTime().getValue(), zone);
                Map<String, BigDecimal> values = new LinkedHashMap<>();
                Instant obsTime = obrTime;
                for (int x = 0; x < oo.getOBSERVATIONReps(); x++) {
                    ORU_R01_OBSERVATION ob = oo.getOBSERVATION(x);
                    OBX obx = ob.getOBX();
                    String code = obx.getObservationIdentifier().getIdentifier().getValue();
                    String codingSystem = obx.getObservationIdentifier().getNameOfCodingSystem().getValue();
                    String status = obx.getObservationResultStatus().getValue();
                    if (!"LN".equals(codingSystem) || "D".equals(status) || "X".equals(status) || obx.getObservationValueReps() == 0) {
                        ignored.add(code);
                        continue;
                    }
                    if (!VITALS.contains(code) && !"20150-9".equals(code) && !"19870-5".equals(code)) {
                        ignored.add(code);
                        continue;
                    }
                    BigDecimal value;
                    try {
                        value = new BigDecimal(obx.getObservationValue(0).getData().encode().trim());
                    } catch (NumberFormatException ex) {
                        throw new Hl7ProcessingException("OBX " + code + " value is not numeric");
                    }
                    if ("8310-5".equals(code) && "[degF]".equals(obx.getUnits().getIdentifier().getValue())) {
                        value = value.subtract(new BigDecimal("32")).multiply(new BigDecimal("5"))
                                .divide(new BigDecimal("9"), 1, RoundingMode.HALF_UP);
                    }
                    values.put(code, value);
                    Instant t2 = Hl7Support.parseInstant(obx.getDateTimeOfTheObservation().getTime().getValue(), zone);
                    if (t2 != null) {
                        obsTime = t2;
                    }
                }
                if (obsTime == null) {
                    obsTime = Instant.now();
                }
                if (obsTime.isAfter(Instant.now().plusSeconds(300))) {
                    throw new Hl7ProcessingException("Observation time is in the future");
                }
                String note = "Imported via HL7 ORU^R01 (control id " + controlId + ")";
                if (values.keySet().stream().anyMatch(VITALS::contains)) {
                    BigDecimal spo2 = values.getOrDefault("59408-5", values.get("2708-6"));
                    var obs = observations.save(new Observation(patient.getId(), null, obsTime,
                            intOf(values.get("9279-1")), intOf(values.get("8867-4")), intOf(values.get("8480-6")),
                            intOf(values.get("8462-4")), values.get("8310-5"), intOf(spo2), null, null, null, null,
                            null, note, system.id()));
                    created.add("Observation#" + obs.getId());
                }
                if (values.containsKey("20150-9") || values.containsKey("19870-5")) {
                    BigDecimal fev1 = scale2(values.get("20150-9"));
                    BigDecimal fvc = scale2(values.get("19870-5"));
                    if (fev1 != null && fvc != null && fev1.compareTo(fvc) > 0) {
                        throw new Hl7ProcessingException("FEV1 exceeds FVC");
                    }
                    var pft = pfts.save(new PulmonaryFunctionTest(patient.getId(), null, obsTime, false, fev1, fvc,
                            null, null, null, null, null, null, note, system.id()));
                    created.add("PulmonaryFunctionTest#" + pft.getId());
                }
            }
        }
        if (patient == null) {
            throw new Hl7ProcessingException("ORU message contains no patient result");
        }
        if (created.isEmpty()) {
            throw new Hl7ProcessingException("No supported LOINC-coded numeric results found");
        }
        String note = "created " + String.join(", ", created) + (ignored.isEmpty() ? "" : "; ignored codes " + ignored);
        return new Result(patient.getId(), "Observation", created.get(0).replaceAll("\\D", ""), note);
    }

    private static Integer intOf(BigDecimal v) {
        return v == null ? null : v.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
