package com.rdss.reports;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.reports.ReportDtos.AppointmentStats;
import com.rdss.reports.ReportDtos.BillingStats;
import com.rdss.reports.ReportDtos.CdssStats;
import com.rdss.reports.ReportDtos.Count;
import com.rdss.reports.ReportDtos.DayCount;
import com.rdss.reports.ReportDtos.ImagingStats;
import com.rdss.reports.ReportDtos.IntegrationStats;
import com.rdss.reports.ReportDtos.MonthAmount;
import com.rdss.reports.ReportDtos.PatientStats;
import com.rdss.reports.ReportDtos.Scope;
import com.rdss.reports.ReportDtos.ServiceRevenue;
import com.rdss.reports.ReportDtos.Summary;
import com.rdss.system.SettingsService;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate dashboards computed directly from the operational tables (no separate warehouse).
 * Administrators see organisation-wide figures including finance and integration; clinicians
 * see the same clinical/operational aggregates restricted to their care-team patients and never
 * see financial or integration data. No patient-level rows are returned from this service.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
public class ReportService {

    static final int MAX_RANGE_DAYS = 731;

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    public ReportService(NamedParameterJdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Validated period in the clinic time zone: [from 00:00, to+1 00:00). */
    record Period(LocalDate from, LocalDate to, Instant start, Instant end) {
    }

    static Period period(LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate t = to == null ? LocalDate.now(zone) : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw new BusinessRuleException("'from' must not be after 'to'");
        }
        if (f.plusDays(MAX_RANGE_DAYS).isBefore(t)) {
            throw new BusinessRuleException("Report period is limited to " + MAX_RANGE_DAYS + " days");
        }
        return new Period(f, t, f.atStartOfDay(zone).toInstant(), t.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** SQL fragment restricting {@code alias.id} (patient id column) to the caller's care team when required. */
    static String scope(String patientIdColumn, boolean careTeam) {
        return careTeam ? " AND " + patientIdColumn
                + " IN (SELECT ct.patient_id FROM patient_care_team ct WHERE ct.clinician_user_id = :me)" : "";
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate fromDate, LocalDate toDate) {
        AuthenticatedUser user = CurrentUser.require();
        boolean careTeam = user.is(Role.CLINICIAN);
        Period p = period(fromDate, toDate, settings.clinicZone());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("me", user.id())
                .addValue("start", Timestamp.from(p.start()))
                .addValue("end", Timestamp.from(p.end()))
                .addValue("fromDate", p.from())
                .addValue("toDate", p.to())
                .addValue("tz", settings.clinicZone().getId());

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("scope", careTeam ? "Figures cover patients on your care team only." : "Organisation-wide figures.");
        notes.put("diagnoses", "Diagnoses recorded in the period, excluding entries marked in error.");
        notes.put("timezone", "Dates are interpreted in the clinic time zone (" + settings.clinicZone().getId() + ").");

        return new Summary(careTeam ? Scope.CARE_TEAM : Scope.ORGANIZATION, p.from(), p.to(), Instant.now(),
                patients(params, careTeam), appointments(params, careTeam), topDiagnoses(params, careTeam, 10),
                imaging(params, careTeam), cdss(params, careTeam),
                careTeam ? null : billing(params), careTeam ? null : integration(params), notes);
    }

    private PatientStats patients(MapSqlParameterSource params, boolean ct) {
        String sc = scope("p.id", ct);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE p.active) AS active,
                       count(*) FILTER (WHERE p.created_at >= :start AND p.created_at < :end) AS new_in_period,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM respiratory_history h WHERE h.patient_id = p.id)) AS with_history
                FROM patient p WHERE 1=1""" + sc, params);
        // Current respiratory history version per patient.
        List<Count> conditions = jdbc.query("""
                WITH cur AS (
                  SELECT DISTINCT ON (h.patient_id) h.* FROM respiratory_history h
                  JOIN patient p ON p.id = h.patient_id WHERE p.active""" + sc + """
                  ORDER BY h.patient_id, h.version_no DESC)
                SELECT 'ASTHMA' AS k, 'Asthma' AS l, count(*) FILTER (WHERE asthma) AS c FROM cur
                UNION ALL SELECT 'COPD', 'COPD', count(*) FILTER (WHERE copd) FROM cur
                UNION ALL SELECT 'BRONCHIECTASIS', 'Bronchiectasis', count(*) FILTER (WHERE bronchiectasis) FROM cur
                UNION ALL SELECT 'ILD', 'Interstitial lung disease', count(*) FILTER (WHERE interstitial_lung_disease) FROM cur
                UNION ALL SELECT 'OSA', 'Obstructive sleep apnoea', count(*) FILTER (WHERE obstructive_sleep_apnea) FROM cur
                UNION ALL SELECT 'HOME_O2', 'Home oxygen', count(*) FILTER (WHERE home_oxygen) FROM cur
                """, params, (rs, i) -> new Count(rs.getString("k"), rs.getString("l"), rs.getLong("c")));
        List<Count> smoking = jdbc.query("SELECT p.smoking_status AS k, count(*) AS c FROM patient p WHERE p.active" + sc
                        + " GROUP BY p.smoking_status ORDER BY c DESC", params,
                (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        return new PatientStats(num(row, "total"), num(row, "active"), num(row, "new_in_period"),
                num(row, "with_history"), conditions, smoking);
    }

    private AppointmentStats appointments(MapSqlParameterSource params, boolean ct) {
        String sc = scope("a.patient_id", ct);
        String where = " FROM appointment a WHERE a.starts_at >= :start AND a.starts_at < :end" + sc;
        List<Count> byStatus = jdbc.query("SELECT a.status AS k, count(*) AS c" + where + " GROUP BY a.status ORDER BY c DESC",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        long total = byStatus.stream().mapToLong(Count::count).sum();
        long completed = countOf(byStatus, "COMPLETED");
        long noShow = countOf(byStatus, "NO_SHOW");
        // Rates are over appointments whose outcome is known (completed or no-show), not cancellations.
        long concluded = completed + noShow;
        List<DayCount> perDay = jdbc.query("SELECT (a.starts_at AT TIME ZONE :tz)::date AS d, count(*) AS c" + where
                        + " AND a.status <> 'CANCELLED' GROUP BY d ORDER BY d", params,
                (rs, i) -> new DayCount(rs.getObject("d", LocalDate.class), rs.getLong("c")));
        return new AppointmentStats(total, byStatus, concluded == 0 ? null : (double) completed / concluded,
                concluded == 0 ? null : (double) noShow / concluded, perDay);
    }

    private List<Count> topDiagnoses(MapSqlParameterSource params, boolean ct, int limit) {
        return jdbc.query("""
                SELECT d.icd10_code AS k, max(d.description) AS l, count(*) AS c FROM diagnosis d
                WHERE d.status <> 'ENTERED_IN_ERROR' AND d.recorded_at >= :start AND d.recorded_at < :end""" + scope("d.patient_id", ct)
                        + " GROUP BY d.icd10_code ORDER BY c DESC, k LIMIT " + limit,
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("l"), rs.getLong("c")));
    }

    private ImagingStats imaging(MapSqlParameterSource params, boolean ct) {
        String sc = scope("s.patient_id", ct);
        long studies = jdbc.queryForObject("SELECT count(*) FROM imaging_study s WHERE s.linked_at >= :start AND s.linked_at < :end" + sc,
                params, Long.class);
        List<Count> byModality = jdbc.query("""
                SELECT coalesce(s.modalities, 'Unknown') AS k, count(*) AS c FROM imaging_study s
                WHERE s.linked_at >= :start AND s.linked_at < :end""" + sc + " GROUP BY k ORDER BY c DESC",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        List<Count> findings = jdbc.query("""
                SELECT f.finding_type AS k, count(*) AS c FROM imaging_finding f JOIN imaging_study s ON s.id = f.study_id
                WHERE f.status <> 'ENTERED_IN_ERROR' AND f.recorded_at >= :start AND f.recorded_at < :end""" + sc
                        + " GROUP BY f.finding_type ORDER BY c DESC",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        long pending = jdbc.queryForObject("SELECT count(*) FROM imaging_order o WHERE o.status IN ('REQUESTED','SCHEDULED')"
                + scope("o.patient_id", ct), params, Long.class);
        return new ImagingStats(studies, byModality, findings, pending);
    }

    private CdssStats cdss(MapSqlParameterSource params, boolean ct) {
        String sc = scope("a.patient_id", ct);
        String where = " FROM clinical_assessment a WHERE a.assessed_at >= :start AND a.assessed_at < :end" + sc;
        Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE a.review_status = 'REVIEWED') AS reviewed"
                + where, params);
        List<Count> bySeverity = jdbc.query("SELECT a.highest_severity AS k, count(*) AS c" + where + " GROUP BY k ORDER BY c DESC",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        List<Count> byRule = jdbc.query("""
                SELECT al.rule_code AS k, count(*) AS c FROM cdss_alert al JOIN clinical_assessment a ON a.id = al.assessment_id
                WHERE a.assessed_at >= :start AND a.assessed_at < :end""" + sc + " GROUP BY al.rule_code ORDER BY c DESC",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        long dismissed = jdbc.queryForObject("""
                SELECT count(*) FROM cdss_alert al JOIN clinical_assessment a ON a.id = al.assessment_id
                WHERE al.disposition = 'DISMISSED' AND a.assessed_at >= :start AND a.assessed_at < :end""" + sc, params, Long.class);
        long total = num(row, "total");
        long reviewed = num(row, "reviewed");
        return new CdssStats(total, reviewed, total - reviewed, bySeverity, byRule, dismissed);
    }

    private BillingStats billing(MapSqlParameterSource params) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT coalesce(sum(i.total), 0) AS invoiced,
                       coalesce(sum(i.total - i.amount_paid), 0) AS outstanding
                FROM invoice i WHERE i.status IN ('ISSUED','PARTIALLY_PAID','PAID')
                  AND i.issue_date BETWEEN :fromDate AND :toDate""", params);
        BigDecimal collected = jdbc.queryForObject("""
                SELECT coalesce(sum(p.amount), 0) FROM payment p
                WHERE p.status = 'COMPLETED' AND p.paid_at >= :start AND p.paid_at < :end""", params, BigDecimal.class);
        BigDecimal simulated = jdbc.queryForObject("""
                SELECT coalesce(sum(p.amount), 0) FROM payment p
                WHERE p.status = 'COMPLETED' AND p.simulated AND p.paid_at >= :start AND p.paid_at < :end""", params, BigDecimal.class);
        long overdue = jdbc.queryForObject("""
                SELECT count(*) FROM invoice i WHERE i.status IN ('ISSUED','PARTIALLY_PAID') AND i.due_date < :toDate
                  AND i.total > i.amount_paid""", params, Long.class);
        List<Count> byStatus = jdbc.query("""
                SELECT i.status AS k, count(*) AS c FROM invoice i
                WHERE i.created_at >= :start AND i.created_at < :end GROUP BY i.status ORDER BY c DESC""",
                params, (rs, i) -> new Count(rs.getString("k"), rs.getString("k"), rs.getLong("c")));
        List<ServiceRevenue> byService = jdbc.query("""
                SELECT coalesce(s.code, 'CUSTOM') AS code, coalesce(s.name, 'Custom lines') AS name,
                       count(*) AS lines, coalesce(sum(it.line_total), 0) AS amount
                FROM invoice_item it JOIN invoice i ON i.id = it.invoice_id
                LEFT JOIN billing_service s ON s.id = it.service_id
                WHERE i.status IN ('ISSUED','PARTIALLY_PAID','PAID') AND i.issue_date BETWEEN :fromDate AND :toDate
                GROUP BY 1, 2 ORDER BY amount DESC""",
                params, (rs, i) -> new ServiceRevenue(rs.getString("code"), rs.getString("name"), rs.getLong("lines"),
                        rs.getBigDecimal("amount")));
        List<MonthAmount> byMonth = jdbc.query("""
                WITH inv AS (SELECT to_char(i.issue_date, 'YYYY-MM') AS m, sum(i.total) AS invoiced FROM invoice i
                             WHERE i.status IN ('ISSUED','PARTIALLY_PAID','PAID') AND i.issue_date BETWEEN :fromDate AND :toDate GROUP BY 1),
                     pay AS (SELECT to_char(p.paid_at AT TIME ZONE :tz, 'YYYY-MM') AS m, sum(p.amount) AS collected FROM payment p
                             WHERE p.status = 'COMPLETED' AND p.paid_at >= :start AND p.paid_at < :end GROUP BY 1)
                SELECT coalesce(inv.m, pay.m) AS m, coalesce(invoiced, 0) AS invoiced, coalesce(collected, 0) AS collected
                FROM inv FULL OUTER JOIN pay ON pay.m = inv.m ORDER BY 1""",
                params, (rs, i) -> new MonthAmount(rs.getString("m"), rs.getBigDecimal("invoiced"), rs.getBigDecimal("collected")));
        return new BillingStats(settings.get("billing.currency", "USD"), (BigDecimal) row.get("invoiced"), collected,
                (BigDecimal) row.get("outstanding"), overdue, byStatus, byService, byMonth, simulated);
    }

    private IntegrationStats integration(MapSqlParameterSource params) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE direction = 'INBOUND') AS received,
                       count(*) FILTER (WHERE direction = 'INBOUND' AND status = 'PROCESSED') AS processed,
                       count(*) FILTER (WHERE direction = 'INBOUND' AND status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE direction = 'INBOUND' AND status = 'REJECTED') AS rejected,
                       coalesce(sum(duplicate_count) FILTER (WHERE direction = 'INBOUND'), 0) AS duplicates,
                       count(*) FILTER (WHERE direction = 'OUTBOUND' AND status = 'SENT') AS sent,
                       count(*) FILTER (WHERE direction = 'OUTBOUND' AND status IN ('FAILED','PENDING')) AS out_failed
                FROM hl7_message WHERE created_at >= :start AND created_at < :end""", params);
        long denied = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE outcome = 'DENIED' AND occurred_at >= :start AND occurred_at < :end",
                params, Long.class);
        return new IntegrationStats(num(r, "received"), num(r, "processed"), num(r, "failed"), num(r, "rejected"),
                num(r, "duplicates"), num(r, "sent"), num(r, "out_failed"), denied);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static long countOf(List<Count> counts, String key) {
        return counts.stream().filter(c -> c.key().equals(key)).mapToLong(Count::count).sum();
    }
}
