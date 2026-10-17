package com.rdss.system;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-managed runtime settings with typed accessors and per-key validation. */
@Service
public class SettingsService {

    public record SettingResponse(String key, String value, String description, java.time.Instant updatedAt,
                                  String updatedBy) {
        static SettingResponse from(SystemSetting s) {
            return new SettingResponse(s.getKey(), s.getValue(), s.getDescription(), s.getUpdatedAt(),
                    s.getUpdatedBy());
        }
    }

    private static final Map<String, Predicate<String>> VALIDATORS = Map.ofEntries(
            Map.entry("appointments.slot_minutes", v -> isIntBetween(v, 5, 240)),
            Map.entry("appointments.patient_cancel_cutoff_hours", v -> isIntBetween(v, 0, 168)),
            Map.entry("appointments.patient_booking_horizon_days", v -> isIntBetween(v, 1, 730)),
            Map.entry("billing.currency", v -> v.matches("^[A-Z]{3}$")),
            Map.entry("billing.default_tax_rate", SettingsService::isRate),
            Map.entry("billing.invoice_prefix", v -> v.matches("^[A-Z0-9]{1,8}$")),
            Map.entry("billing.payment_terms_days", v -> isIntBetween(v, 0, 365)),
            Map.entry("clinic.timezone", SettingsService::isZone),
            Map.entry("clinic.name", v -> !v.isBlank() && v.length() <= 200),
            Map.entry("hl7.rdss_assigning_authority", v -> v.matches("^[A-Za-z0-9._-]{1,64}$")),
            Map.entry("hl7.retry.max_attempts", v -> isIntBetween(v, 1, 20)),
            Map.entry("hl7.outbound.enabled", v -> v.equals("true") || v.equals("false")),
            Map.entry("hl7.outbound.host", v -> v.isEmpty() || v.matches("^[A-Za-z0-9.-]{1,253}$")),
            Map.entry("hl7.outbound.port", v -> isIntBetween(v, 1, 65535)));

    private final SystemSettingRepository repository;
    private final AuditService audit;

    public SettingsService(SystemSettingRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<SettingResponse> list() {
        return repository.findAll(Sort.by("key")).stream().map(SettingResponse::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public SettingResponse update(String key, String value) {
        SystemSetting s = repository.findById(key).orElseThrow(() -> NotFoundException.of("Setting", key));
        String v = value == null ? "" : value.trim();
        Predicate<String> validator = VALIDATORS.getOrDefault(key, x -> !x.isBlank() && x.length() <= 1000);
        if (!validator.test(v)) {
            throw new BusinessRuleException("Invalid value for setting " + key);
        }
        String old = s.getValue();
        s.update(v, CurrentUser.require().username());
        audit.record("UPDATE", "SystemSetting", key, null, Outcome.SUCCESS, old + " -> " + v);
        return SettingResponse.from(s);
    }

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return repository.findById(key).map(SystemSetting::getValue).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        try {
            return new BigDecimal(get(key, defaultValue.toPlainString()));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public ZoneId clinicZone() {
        String z = get("clinic.timezone", "UTC");
        return isZone(z) ? ZoneId.of(z) : ZoneId.of("UTC");
    }

    private static boolean isIntBetween(String v, int min, int max) {
        try {
            int i = Integer.parseInt(v);
            return i >= min && i <= max;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isRate(String v) {
        try {
            BigDecimal d = new BigDecimal(v);
            return d.signum() >= 0 && d.compareTo(BigDecimal.ONE) < 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isZone(String v) {
        try {
            ZoneId.of(v);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }
}
