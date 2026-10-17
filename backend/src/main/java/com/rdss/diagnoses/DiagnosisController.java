package com.rdss.diagnoses;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.diagnoses.DiagnosisDtos.DiagnosisRequest;
import com.rdss.diagnoses.DiagnosisDtos.DiagnosisResponse;
import com.rdss.diagnoses.DiagnosisDtos.Icd10Response;
import com.rdss.diagnoses.DiagnosisDtos.ImportResult;
import com.rdss.diagnoses.DiagnosisDtos.ReasonRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Diagnoses & ICD-10")
public class DiagnosisController {

    private final Icd10Service icd10;
    private final DiagnosisService diagnoses;

    public DiagnosisController(Icd10Service icd10, DiagnosisService diagnoses) {
        this.icd10 = icd10;
        this.diagnoses = diagnoses;
    }

    @GetMapping("/icd10")
    @Operation(summary = "Search ICD-10-CM codes by code prefix or description")
    public PageResponse<Icd10Response> search(@RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "true") boolean billableOnly,
                                              @RequestParam(defaultValue = "false") boolean includeInactive,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return icd10.search(q, billableOnly, includeInactive, Pages.of(page, size, Sort.unsorted()));
    }

    @GetMapping("/icd10/{code}")
    public Icd10Response code(@PathVariable String code) {
        return icd10.get(code);
    }

    @PutMapping("/icd10/{code}/active")
    @Operation(summary = "Enable or disable a code for new records (administrators)")
    public Icd10Response setActive(@PathVariable String code, @RequestParam boolean active) {
        return icd10.setActive(code, active);
    }

    @PostMapping(path = "/icd10/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import the official CDC/NCHS ICD-10-CM order file (administrators)")
    public ImportResult importCodes(@RequestPart("file") MultipartFile file, @RequestParam String codeVersion) {
        return icd10.importOrderFile(file, codeVersion);
    }

    @GetMapping("/patients/{patientId}/diagnoses")
    public List<DiagnosisResponse> list(@PathVariable Long patientId) {
        return diagnoses.list(patientId);
    }

    @PostMapping("/patients/{patientId}/diagnoses")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisResponse record(@PathVariable Long patientId, @Valid @RequestBody DiagnosisRequest request) {
        return diagnoses.record(patientId, request);
    }

    @PostMapping("/diagnoses/{id}/resolve")
    public DiagnosisResponse resolve(@PathVariable Long id) {
        return diagnoses.resolve(id);
    }

    @PostMapping("/diagnoses/{id}/entered-in-error")
    public DiagnosisResponse enteredInError(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return diagnoses.enteredInError(id, request.reason());
    }
}
