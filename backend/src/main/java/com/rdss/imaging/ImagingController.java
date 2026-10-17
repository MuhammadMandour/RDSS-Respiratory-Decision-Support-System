package com.rdss.imaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.rdss.imaging.ImagingDtos.ErrorRequest;
import com.rdss.imaging.ImagingDtos.FindingRequest;
import com.rdss.imaging.ImagingDtos.FindingResponse;
import com.rdss.imaging.ImagingDtos.LinkStudyRequest;
import com.rdss.imaging.ImagingDtos.OrderRequest;
import com.rdss.imaging.ImagingDtos.OrderResponse;
import com.rdss.imaging.ImagingDtos.StudyResponse;
import com.rdss.imaging.ImagingDtos.StudySummary;
import com.rdss.imaging.ImagingDtos.UploadResult;
import com.rdss.imaging.ImagingDtos.ViewerSessionRequest;
import com.rdss.imaging.ImagingDtos.ViewerSessionResponse;
import com.rdss.imaging.viewer.ViewerSessionService;
import com.rdss.imaging.viewer.ViewerTokenService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Imaging")
public class ImagingController {

    private final ImagingService imaging;
    private final ViewerSessionService viewer;

    public ImagingController(ImagingService imaging, ViewerSessionService viewer) {
        this.imaging = imaging;
        this.viewer = viewer;
    }

    @GetMapping("/patients/{patientId}/imaging-orders")
    public List<OrderResponse> orders(@PathVariable Long patientId) {
        return imaging.listOrders(patientId);
    }

    @PostMapping("/patients/{patientId}/imaging-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse order(@PathVariable Long patientId, @Valid @RequestBody OrderRequest request) {
        return imaging.createOrder(patientId, request);
    }

    @PostMapping("/imaging-orders/{id}/cancel")
    public OrderResponse cancelOrder(@PathVariable Long id) {
        return imaging.cancelOrder(id);
    }

    @GetMapping("/patients/{patientId}/imaging-studies")
    public List<StudySummary> studies(@PathVariable Long patientId) {
        return imaging.listStudies(patientId);
    }

    @PostMapping(path = "/patients/{patientId}/imaging-studies/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload DICOM Part-10 files; they are stored in Orthanc and linked to the patient")
    public UploadResult upload(@PathVariable Long patientId, @RequestPart("files") List<MultipartFile> files,
                               @RequestParam(required = false) Long encounterId,
                               @RequestParam(required = false) Long orderId) {
        return imaging.upload(patientId, files, encounterId, orderId);
    }

    @PostMapping("/patients/{patientId}/imaging-studies/link")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link a study already present in the imaging archive (e.g. received via DICOM C-STORE)")
    public StudyResponse link(@PathVariable Long patientId, @Valid @RequestBody LinkStudyRequest request) {
        return imaging.linkExisting(patientId, request);
    }

    @GetMapping("/imaging-studies/{id}")
    public StudyResponse study(@PathVariable Long id) {
        return imaging.getStudy(id);
    }

    @PostMapping("/imaging-studies/{id}/findings")
    @ResponseStatus(HttpStatus.CREATED)
    public FindingResponse addFinding(@PathVariable Long id, @Valid @RequestBody FindingRequest request) {
        return imaging.addFinding(id, request);
    }

    @PostMapping("/imaging-findings/{id}/finalize")
    public FindingResponse finalizeFinding(@PathVariable Long id) {
        return imaging.finalizeFinding(id);
    }

    @PostMapping("/imaging-findings/{id}/entered-in-error")
    public FindingResponse findingInError(@PathVariable Long id, @Valid @RequestBody ErrorRequest request) {
        return imaging.findingInError(id, request.reason());
    }

    @PostMapping("/imaging/viewer-sessions")
    @Operation(summary = "Authorize studies for the integrated viewer; sets a short-lived, study-scoped cookie")
    public ViewerSessionResponse openViewer(@Valid @RequestBody ViewerSessionRequest request, HttpServletRequest req,
                                            HttpServletResponse res) {
        ViewerSessionService.Session s = viewer.open(request.studyIds());
        ResponseCookie cookie = ResponseCookie.from(ViewerTokenService.COOKIE, s.token())
                .httpOnly(true)
                .secure(req.isSecure())
                .sameSite("Strict")
                .path(ViewerTokenService.COOKIE_PATH)
                .maxAge(Duration.between(Instant.now(), s.response().expiresAt()))
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return s.response();
    }
}
