package com.rdss.imaging.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.rdss.config.RdssProperties;

import io.swagger.v3.oas.annotations.Hidden;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Read-only DICOMweb (QIDO-RS / WADO-RS) proxy in front of Orthanc. Authorization comes from
 * the study-scoped viewer cookie; Orthanc credentials never leave the backend.
 */
@Hidden
@RestController
public class DicomWebProxyController {

    private static final Logger log = LoggerFactory.getLogger(DicomWebProxyController.class);
    private static final String PREFIX = ViewerTokenService.COOKIE_PATH;
    private static final Pattern ORTHANC_ROOT = Pattern.compile("https?://[^/\"]+/dicom-web/");
    private static final List<String> PASS_RESPONSE_HEADERS = List.of("Content-Type", "Content-Length", "Content-Encoding");

    private final ViewerTokenService tokens;
    private final HttpClient http;
    private final String orthancDicomWebRoot;
    private final String basicAuth;

    public DicomWebProxyController(ViewerTokenService tokens, RdssProperties props) {
        this.tokens = tokens;
        RdssProperties.Orthanc cfg = props.orthanc();
        this.http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
        this.orthancDicomWebRoot = cfg.url().replaceAll("/+$", "") + "/dicom-web";
        this.basicAuth = cfg.username() == null ? null : "Basic " + Base64.getEncoder().encodeToString(
                (cfg.username() + ":" + (cfg.password() == null ? "" : cfg.password())).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping(PREFIX + "/**")
    public void proxy(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length() + PREFIX.length());
        Optional<ViewerTokenService.Grant> grant = tokens.validate(cookie(req));
        if (grant.isEmpty()) {
            res.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        Optional<DicomWebAccessPolicy.Decision> decision = DicomWebAccessPolicy.authorize(path, req.getParameterMap(),
                req.getQueryString(), grant.get().studyInstanceUids());
        if (decision.isEmpty()) {
            res.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }

        String query = decision.get().narrowedQuery().orElse(req.getQueryString());
        String target = orthancDicomWebRoot + path + (query == null ? "" : "?" + query);
        HttpRequest.Builder upstream = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(120)).GET();
        String accept = req.getHeader("Accept");
        if (accept != null) {
            upstream.header("Accept", accept);
        }
        if (basicAuth != null) {
            upstream.header("Authorization", basicAuth);
        }
        HttpResponse<InputStream> r;
        try {
            r = http.send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            res.sendError(HttpStatus.BAD_GATEWAY.value());
            return;
        } catch (IOException ex) {
            log.warn("DICOMweb upstream unreachable: {}", ex.getMessage());
            res.sendError(HttpStatus.BAD_GATEWAY.value());
            return;
        }

        res.setStatus(r.statusCode());
        String contentType = r.headers().firstValue("Content-Type").orElse("");
        res.setHeader("Cache-Control", "private, no-store");
        try (InputStream in = r.body(); OutputStream out = res.getOutputStream()) {
            if (contentType.contains("json")) {
                // Rewrite Orthanc's absolute BulkDataURIs so the viewer fetches them through this proxy.
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String publicRoot = ServletUriComponentsBuilder.fromContextPath(req).path(PREFIX).toUriString() + "/";
                byte[] rewritten = ORTHANC_ROOT.matcher(body).replaceAll(publicRoot).getBytes(StandardCharsets.UTF_8);
                res.setContentType(contentType);
                res.setContentLength(rewritten.length);
                out.write(rewritten);
            } else {
                for (String h : PASS_RESPONSE_HEADERS) {
                    r.headers().firstValue(h).ifPresent(v -> res.setHeader(h, v));
                }
                in.transferTo(out);
            }
        }
    }

    private static String cookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(c -> ViewerTokenService.COOKIE.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
}
