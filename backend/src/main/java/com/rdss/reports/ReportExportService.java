package com.rdss.reports;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.rdss.audit.AuditService;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.reports.ReportService.Period;
import com.rdss.system.SettingsService;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV exports. Clinical datasets are aggregate counts only (no patient-level rows) and are
 * scoped to the clinician's care team; financial and integration datasets are administrator-only
 * and carry no patient names or clinical content. Every export is audited.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
public class ReportExportService {

    public enum Dataset {
        APPOINTMENTS_DAILY("appointments-daily", false),
        DIAGNOSES("diagnoses", false),
        CDSS_ALERTS("cdss-alerts", false),
        IMAGING_FINDINGS("imaging-findings", false),
        REVENUE_BY_SERVICE("revenue-by-service", true),
        INVOICES("invoices", true),
        HL7_MESSAGES("hl7-messages", true);

        final String slug;
        final boolean adminOnly;

        Dataset(String slug, boolean adminOnly) {
            this.slug = slug;
            this.adminOnly = adminOnly;
        }

        public String slug() {
            return slug;
        }

        public boolean adminOnly() {
            return adminOnly;
        }

        static Dataset fromSlug(String slug) {
            return Arrays.stream(values()).filter(d -> d.slug.equals(slug.toLowerCase(Locale.ROOT))).findFirst()
                    .orElseThrow(() -> new NotFoundException("Unknown report dataset"));
        }
    }

    public record Export(String filename, String content) {
    }

    private static final int MAX_ROWS = 50_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    public ReportExportService(NamedParameterJdbcTemplate jdbc, SettingsService settings,
                               AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Export export(String slug, LocalDate from, LocalDate to) {
        AuthenticatedUser user = CurrentUser.require();
        Dataset dataset = Dataset.fromSlug(slug);
        if (dataset.adminOnly && !user.is(Role.ADMIN)) {
            audit.denied("REPORT_EXPORT", "REPORT", dataset.slug, null);
            throw new AccessDeniedException("This report is restricted to administrators");
        }
        boolean ct = user.is(Role.CLINICIAN);
        Period p = ReportService.period(from, to, settings.clinicZone());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("me", user.id())
                .addValue("start", Timestamp.from(p.start()))
                .addValue("end", Timestamp.from(p.end()))
                .addValue("fromDate", p.from())
                .addValue("toDate", p.to())
                .addValue("tz", settings.clinicZone().getId())
                .addValue("maxRows", MAX_ROWS);

        CsvWriter csv = new CsvWriter();
        switch (dataset) {
            case APPOINTMENTS_DAILY -> {
                csv.row("date", "status", "appointments");
                jdbc.query("SELECT (a.starts_at AT TIME ZONE :tz)::date AS d, a.status, count(*) AS c FROM appointment a"
                                + " WHERE a.starts_at >= :start AND a.starts_at < :end" + ReportService.scope("a.patient_id", ct)
                                + " GROUP BY d, a.status ORDER BY d, a.status",
                        params, rs -> {
                            csv.row(rs.getObject("d", LocalDate.class).toString(), rs.getString("status"), rs.getLong("c"));
                        });
            }
            case DIAGNOSES -> {
                csv.row("icd10_code", "description", "code_version", "diagnosis_type", "status", "count", "distinct_patients");
                jdbc.query("""
                        SELECT d.icd10_code, max(d.description) AS description, d.code_version, d.diagnosis_type, d.status,
                               count(*) AS c, count(DISTINCT d.patient_id) AS patients
                        FROM diagnosis d WHERE d.status <> 'ENTERED_IN_ERROR' AND d.recorded_at >= :start AND d.recorded_at < :end"""
                                + ReportService.scope("d.patient_id", ct)
                                + " GROUP BY d.icd10_code, d.code_version, d.diagnosis_type, d.status ORDER BY c DESC, d.icd10_code",
                        params, rs -> {
                            csv.row(rs.getString("icd10_code"), rs.getString("description"), rs.getString("code_version"),
                                    rs.getString("diagnosis_type"), rs.getString("status"), rs.getLong("c"), rs.getLong("patients"));
                        });
            }
            case CDSS_ALERTS -> {
                csv.row("rule_code", "severity", "disposition", "alerts");
                jdbc.query("""
                        SELECT al.rule_code, al.severity, al.disposition, count(*) AS c
                        FROM cdss_alert al JOIN clinical_assessment a ON a.id = al.assessment_id
                        WHERE a.assessed_at >= :start AND a.assessed_at < :end""" + ReportService.scope("a.patient_id", ct)
                                + " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3",
                        params, rs -> {
                            csv.row(rs.getString("rule_code"), rs.getString("severity"), rs.getString("disposition"), rs.getLong("c"));
                        });
            }
            case IMAGING_FINDINGS -> {
                csv.row("finding_type", "source", "status", "findings");
                jdbc.query("""
                        SELECT f.finding_type, f.source, f.status, count(*) AS c
                        FROM imaging_finding f JOIN imaging_study s ON s.id = f.study_id
                        WHERE f.status <> 'ENTERED_IN_ERROR' AND f.recorded_at >= :start AND f.recorded_at < :end"""
                                + ReportService.scope("s.patient_id", ct) + " GROUP BY 1, 2, 3 ORDER BY 4 DESC",
                        params, rs -> {
                            csv.row(rs.getString("finding_type"), rs.getString("source"), rs.getString("status"), rs.getLong("c"));
                        });
            }
            case REVENUE_BY_SERVICE -> {
                csv.row("service_code", "service_name", "lines", "quantity", "subtotal", "discount", "tax", "total", "currency");
                String currency = settings.get("billing.currency", "USD");
                jdbc.query("""
                        SELECT coalesce(s.code, 'CUSTOM') AS code, coalesce(s.name, 'Custom lines') AS name, count(*) AS lines,
                               sum(it.quantity) AS qty, sum(it.line_subtotal) AS sub, sum(it.line_discount) AS disc,
                               sum(it.line_tax) AS tax, sum(it.line_total) AS total
                        FROM invoice_item it JOIN invoice i ON i.id = it.invoice_id
                        LEFT JOIN billing_service s ON s.id = it.service_id
                        WHERE i.status IN ('ISSUED','PARTIALLY_PAID','PAID') AND i.issue_date BETWEEN :fromDate AND :toDate
                        GROUP BY 1, 2 ORDER BY total DESC""",
                        params, rs -> {
                            csv.row(rs.getString("code"), rs.getString("name"), rs.getLong("lines"), rs.getBigDecimal("qty"),
                                    rs.getBigDecimal("sub"), rs.getBigDecimal("disc"), rs.getBigDecimal("tax"),
                                    rs.getBigDecimal("total"), currency);
                        });
            }
            case INVOICES -> {
                // Invoice number is the lookup key; patient names and clinical content are deliberately omitted.
                csv.row("invoice_number", "status", "issue_date", "due_date", "currency", "subtotal", "discount", "tax",
                        "total", "paid", "outstanding", "simulated_payments");
                jdbc.query("""
                        SELECT i.invoice_number, i.status, i.issue_date, i.due_date, i.currency, i.subtotal, i.discount_total,
                               i.tax_total, i.total, i.amount_paid, i.total - i.amount_paid AS outstanding,
                               (SELECT coalesce(sum(p.amount), 0) FROM payment p WHERE p.invoice_id = i.id
                                  AND p.status = 'COMPLETED' AND p.simulated) AS simulated
                        FROM invoice i WHERE i.status <> 'DRAFT' AND i.issue_date BETWEEN :fromDate AND :toDate
                        ORDER BY i.issue_date, i.invoice_number LIMIT :maxRows""",
                        params, rs -> {
                            csv.row(rs.getString("invoice_number"), rs.getString("status"), str(rs.getObject("issue_date", LocalDate.class)),
                                    str(rs.getObject("due_date", LocalDate.class)), rs.getString("currency").trim(),
                                    rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount_total"), rs.getBigDecimal("tax_total"),
                                    rs.getBigDecimal("total"), rs.getBigDecimal("amount_paid"), rs.getBigDecimal("outstanding"),
                                    rs.getBigDecimal("simulated"));
                        });
            }
            case HL7_MESSAGES -> {
                // Metadata only: raw message bodies (which contain demographics) are never exported.
                csv.row("id", "created_at", "direction", "transport", "message_type", "control_id", "sending_application",
                        "sending_facility", "status", "ack_code", "attempts", "duplicates", "error");
                jdbc.query("""
                        SELECT id, created_at, direction, transport, message_type, control_id, sending_application,
                               sending_facility, status, ack_code, attempts, duplicate_count, error_message
                        FROM hl7_message WHERE created_at >= :start AND created_at < :end ORDER BY id LIMIT :maxRows""",
                        params, rs -> {
                            csv.row(rs.getLong("id"), rs.getTimestamp("created_at").toInstant().toString(), rs.getString("direction"),
                                    rs.getString("transport"), rs.getString("message_type"), rs.getString("control_id"),
                                    rs.getString("sending_application"), rs.getString("sending_facility"), rs.getString("status"),
                                    rs.getString("ack_code"), rs.getInt("attempts"), rs.getInt("duplicate_count"),
                                    rs.getString("error_message"));
                        });
            }
            default -> throw new IllegalStateException("Unhandled dataset " + dataset);
        }
        audit.success("REPORT_EXPORT", "REPORT", dataset.slug + " " + p.from() + ".." + p.to(), null);
        return new Export("rdss-" + dataset.slug + "-" + p.from() + "-to-" + p.to() + ".csv", csv.toString());
    }

    public List<Dataset> available() {
        AuthenticatedUser user = CurrentUser.require();
        return Arrays.stream(Dataset.values()).filter(d -> !d.adminOnly || user.is(Role.ADMIN)).toList();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
