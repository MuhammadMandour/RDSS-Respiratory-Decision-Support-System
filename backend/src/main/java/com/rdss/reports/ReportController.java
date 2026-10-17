package com.rdss.reports;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.rdss.reports.ReportDtos.Summary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    public record DatasetInfo(String slug, boolean adminOnly) {
    }

    private final ReportService reports;
    private final ReportExportService exports;

    public ReportController(ReportService reports, ReportExportService exports) {
        this.reports = reports;
        this.exports = exports;
    }

    @GetMapping("/summary")
    @Operation(summary = "Aggregate dashboard; organisation-wide for admins, care-team scoped for clinicians")
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(from, to);
    }

    @GetMapping("/exports")
    @Operation(summary = "CSV datasets available to the caller")
    public List<DatasetInfo> datasets() {
        return exports.available().stream().map(d -> new DatasetInfo(d.slug(), d.adminOnly())).toList();
    }

    @GetMapping(value = "/exports/{dataset}", produces = "text/csv")
    @Operation(summary = "Download a CSV dataset for the period")
    public ResponseEntity<byte[]> export(@PathVariable String dataset,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportExportService.Export export = exports.export(dataset, from, to);
        // UTF-8 BOM so spreadsheet tools detect the encoding of non-ASCII descriptions.
        byte[] body = ("﻿" + export.content()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
