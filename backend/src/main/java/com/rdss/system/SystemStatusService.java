package com.rdss.system;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.rdss.imaging.orthanc.ImagingArchive;
import com.rdss.system.SystemStatusResponse.DependencyStatus;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {

    static final String UP = "UP";
    static final String DOWN = "DOWN";

    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;
    private final ImagingArchive orthancClient;
    private final Optional<BuildProperties> buildProperties;

    public SystemStatusService(JdbcTemplate jdbcTemplate, Flyway flyway, ImagingArchive orthancClient,
                               ObjectProvider<BuildProperties> buildProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = flyway;
        this.orthancClient = orthancClient;
        this.buildProperties = Optional.ofNullable(buildProperties.getIfAvailable());
    }

    public SystemStatusResponse status() {
        List<DependencyStatus> deps = new ArrayList<>();
        deps.add(databaseStatus());
        deps.add(imagingStatus());
        String overall = deps.stream().allMatch(d -> UP.equals(d.status())) ? UP : "DEGRADED";
        return new SystemStatusResponse(
                "RDSS",
                buildProperties.map(BuildProperties::getVersion).orElse("dev"),
                overall,
                Instant.now(),
                deps);
    }

    private DependencyStatus databaseStatus() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            MigrationInfo current = flyway.info().current();
            String detail = current == null ? "no migrations applied" : "schema v" + current.getVersion();
            return new DependencyStatus("database", UP, detail);
        } catch (RuntimeException ex) {
            return new DependencyStatus("database", DOWN, "unreachable");
        }
    }

    private DependencyStatus imagingStatus() {
        try {
            var info = orthancClient.systemInfo();
            Object version = info == null ? null : info.get("Version");
            return new DependencyStatus("imaging", UP, version == null ? "reachable" : "Orthanc " + version);
        } catch (RuntimeException ex) {
            return new DependencyStatus("imaging", DOWN, "unreachable");
        }
    }
}
