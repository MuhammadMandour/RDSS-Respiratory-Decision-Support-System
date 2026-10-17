package com.rdss.diagnoses;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.diagnoses.DiagnosisDtos.Icd10Response;
import com.rdss.diagnoses.DiagnosisDtos.ImportResult;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** ICD-10-CM catalogue: lookup, administration and import of the official release file. */
@Service
public class Icd10Service {

    static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("^[A-Za-z0-9 ._-]{2,20}$");

    private final Icd10CodeRepository codes;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public Icd10Service(Icd10CodeRepository codes, JdbcTemplate jdbc, AuditService audit) {
        this.codes = codes;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<Icd10Response> search(String query, boolean billableOnly, boolean includeInactive,
                                              Pageable pageable) {
        String q = query == null || query.isBlank() ? null : query.trim();
        String prefix = q == null ? null : q.toUpperCase(Locale.ROOT) + "%";
        String text = q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%";
        return PageResponse.of(codes.search(q, prefix, text, billableOnly, includeInactive, pageable),
                Icd10Response::from);
    }

    @Transactional(readOnly = true)
    public Icd10Response get(String code) {
        return Icd10Response.from(require(code));
    }

    /** Loads a code that may be used for new clinical or billing records. */
    @Transactional(readOnly = true)
    public Icd10Code requireUsable(String code) {
        Icd10Code c = require(code);
        if (!c.isActive()) {
            throw new BusinessRuleException("ICD-10 code " + code + " is not enabled for use");
        }
        if (!c.isBillable()) {
            throw new BusinessRuleException("ICD-10 code " + code + " is a category header; use a more specific code");
        }
        return c;
    }

    private Icd10Code require(String code) {
        return codes.findById(code == null ? "" : code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("ICD-10 code " + code + " is not in the catalogue"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Icd10Response setActive(String code, boolean active) {
        Icd10Code c = require(code);
        c.setActive(active);
        audit.record(active ? "ENABLE" : "DISABLE", "Icd10Code", c.getCode(), null, Outcome.SUCCESS, null);
        return Icd10Response.from(c);
    }

    /**
     * Upserts the official CDC/NCHS order file. Existing codes are updated (and re-labelled as
     * OFFICIAL_IMPORT); codes already referenced by diagnoses are never deleted.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ImportResult importOrderFile(MultipartFile file, String codeVersion) {
        if (codeVersion == null || !VERSION.matcher(codeVersion.trim()).matches()) {
            throw new BusinessRuleException("codeVersion is required, e.g. FY2026");
        }
        Icd10ImportParser.Result parsed;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            parsed = Icd10ImportParser.parse(r);
        } catch (IOException ex) {
            throw new BusinessRuleException("Could not read the uploaded file");
        }
        if (parsed.entries().isEmpty()) {
            throw new BusinessRuleException("No valid ICD-10-CM entries found; expected the official order-file format");
        }
        String version = codeVersion.trim();
        jdbc.batchUpdate("""
                INSERT INTO icd10_code (code, description, billable, code_version, source, active, updated_at)
                VALUES (?, ?, ?, ?, 'OFFICIAL_IMPORT', TRUE, now())
                ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description, billable = EXCLUDED.billable,
                    code_version = EXCLUDED.code_version, source = 'OFFICIAL_IMPORT', updated_at = now()
                """, parsed.entries(), 1000, (ps, e) -> {
            ps.setString(1, e.code());
            ps.setString(2, e.description());
            ps.setBoolean(3, e.billable());
            ps.setString(4, version);
        });
        int billable = (int) parsed.entries().stream().filter(Icd10ImportParser.Entry::billable).count();
        audit.record("IMPORT", "Icd10Code", version, null, Outcome.SUCCESS,
                "entries=" + parsed.entries().size() + " rejected=" + parsed.rejectedLines().size());
        List<String> samples = parsed.rejectedLines().stream().limit(10).toList();
        return new ImportResult(version, parsed.entries().size(), billable, parsed.rejectedLines().size(), samples);
    }
}
