package com.rdss.imaging.viewer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.security.CurrentUser;
import com.rdss.imaging.ImagingDtos.ViewerSessionResponse;
import com.rdss.imaging.ImagingService;
import com.rdss.imaging.model.ImagingStudy;
import com.rdss.patients.PatientAccessPolicy.Access;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/** Authorizes a set of studies for viewing and issues the scoped viewer grant. */
@Service
public class ViewerSessionService {

    public static final String VIEWER_BASE = "/ohif/viewer";
    static final String NOTICE = "Viewer measurements and annotations are not saved by RDSS. Record findings in RDSS. "
            + "Viewer processing (windowing, MPR, 3D) is display-only; original DICOM data is unchanged.";

    public record Session(ViewerSessionResponse response, String token) {
    }

    private final ImagingService imaging;
    private final ViewerTokenService tokens;
    private final AuditService audit;

    public ViewerSessionService(ImagingService imaging, ViewerTokenService tokens, AuditService audit) {
        this.imaging = imaging;
        this.tokens = tokens;
        this.audit = audit;
    }

    /** All studies must be readable by the caller and belong to the same patient (e.g. current vs prior). */
    @Transactional(readOnly = true)
    public Session open(List<Long> studyIds) {
        Set<Long> unique = new LinkedHashSet<>(studyIds);
        List<ImagingStudy> loaded = new ArrayList<>();
        for (Long id : unique) {
            loaded.add(imaging.requireStudy(id, Access.CLINICAL_READ));
        }
        if (loaded.stream().map(ImagingStudy::getPatientId).distinct().count() > 1) {
            throw new BusinessRuleException("Studies from different patients cannot be opened together");
        }
        List<String> uids = loaded.stream().map(ImagingStudy::getStudyInstanceUid).toList();
        var grant = tokens.issue(CurrentUser.require().id(), uids);
        String url = UriComponentsBuilder.fromPath(VIEWER_BASE)
                .queryParam("StudyInstanceUIDs", String.join(",", uids)).build().toUriString();
        Long patientId = loaded.get(0).getPatientId();
        for (ImagingStudy s : loaded) {
            audit.record("VIEW", "ImagingStudy", s.getId(), patientId, Outcome.SUCCESS, "viewer session");
        }
        return new Session(new ViewerSessionResponse(url, grant.expiresAt(), uids, NOTICE), grant.token());
    }
}
