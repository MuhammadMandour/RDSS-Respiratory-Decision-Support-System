package com.rdss.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReportDtos {

    private ReportDtos() {
    }

    /** "ORGANIZATION" for administrators; "CARE_TEAM" for clinicians (only their care-team patients). */
    public enum Scope { ORGANIZATION, CARE_TEAM }

    public record Count(String key, String label, long count) {
    }

    public record DayCount(LocalDate date, long count) {
    }

    public record PatientStats(long total, long active, long newInPeriod, long withRespiratoryHistory,
                               List<Count> conditions, List<Count> smoking) {
    }

    public record AppointmentStats(long total, List<Count> byStatus, Double completionRate, Double noShowRate,
                                   List<DayCount> perDay) {
    }

    public record ImagingStats(long studies, List<Count> byModality, List<Count> findingsByType, long pendingOrders) {
    }

    public record CdssStats(long assessments, long reviewed, long pendingReview, List<Count> byHighestSeverity,
                            List<Count> alertsByRule, long dismissedAlerts) {
    }

    public record MonthAmount(String month, BigDecimal invoiced, BigDecimal collected) {
    }

    public record ServiceRevenue(String code, String name, long lines, BigDecimal amount) {
    }

    public record BillingStats(String currency, BigDecimal invoiced, BigDecimal collected, BigDecimal outstanding,
                               long overdueInvoices, List<Count> byStatus, List<ServiceRevenue> byService,
                               List<MonthAmount> byMonth, BigDecimal simulatedPayments) {
    }

    public record IntegrationStats(long hl7Received, long hl7Processed, long hl7Failed, long hl7Rejected,
                                   long hl7Duplicates, long outboundSent, long outboundFailed, long deniedAccess) {
    }

    /** Billing and integration sections are only present for administrators. */
    public record Summary(Scope scope, LocalDate from, LocalDate to, Instant generatedAt, PatientStats patients,
                          AppointmentStats appointments, List<Count> topDiagnoses, ImagingStats imaging,
                          CdssStats cdss, BillingStats billing, IntegrationStats integration,
                          Map<String, String> notes) {
    }
}
