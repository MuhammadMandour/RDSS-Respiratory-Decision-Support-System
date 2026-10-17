package com.rdss.cdss.rules;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.ImagingFindingFacts;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/**
 * Surfaces documented imaging findings as context for the assessment. RDSS does not interpret
 * images; this rule only restates what a clinician or radiologist recorded, with its provenance.
 */
@Component
public class ImagingFindingsRule implements ClinicalRule {

    public static final String CODE = "IMAGING_FINDINGS";

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "IMAGING"; }
    @Override public List<String> guidelineCodes() { return List.of(); }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        if (ctx.imagingFindings() == null || ctx.imagingFindings().isEmpty()) {
            return out;
        }
        int lookbackDays = ClinicalRule.intParam(params, "lookbackDays", 180);
        for (ImagingFindingFacts f : ctx.imagingFindings()) {
            if (Duration.between(f.recordedAt(), ctx.assessedAt()).toDays() > lookbackDays) {
                continue;
            }
            boolean unverified = "AI_UNVALIDATED".equals(f.source()) || "DEMONSTRATION".equals(f.source());
            String provenance = switch (f.source()) {
                case "RADIOLOGIST_REPORT" -> "documented from a radiologist report";
                case "CLINICIAN" -> "documented by a clinician";
                case "DEMONSTRATION" -> "DEMONSTRATION DATA - not a real report";
                default -> "UNVALIDATED automated output - not a diagnosis";
            };
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("imagingFindingId", f.findingId());
            evidence.put("imagingStudyId", f.studyId());
            evidence.put("studyDate", f.studyDate() == null ? null : f.studyDate().toString());
            evidence.put("modalities", f.modalities());
            evidence.put("source", f.source());
            evidence.put("status", f.status());
            evidence.values().removeIf(java.util.Objects::isNull);
            String what = humanize(f.type()) + ("NOT_APPLICABLE".equals(f.laterality()) ? "" : " (" + f.laterality().toLowerCase() + ")")
                    + (f.sizeMm() == null ? "" : ", " + f.sizeMm() + " mm");
            out.add(new Finding(CODE, category(), unverified ? Severity.INFO : Severity.LOW,
                    "Imaging: " + what,
                    what + " on " + (f.modalities() == null ? "imaging" : f.modalities())
                            + (f.studyDate() == null ? "" : " dated " + f.studyDate()) + ", " + provenance
                            + " (" + f.status().toLowerCase() + "): " + f.description().replaceAll("[.\\s]+$", "")
                            + ". RDSS does not interpret images; this restates the documented finding.",
                    unverified ? "Confirm against the images and a formal report before relying on this finding." : null,
                    evidence, List.of()));
        }
        return out;
    }

    private static String humanize(String s) {
        String t = s.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
