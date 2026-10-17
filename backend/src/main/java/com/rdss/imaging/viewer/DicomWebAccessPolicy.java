package com.rdss.imaging.viewer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether, and how, a DICOMweb request may be forwarded. Pure function, unit-tested.
 * Allowed shapes (GET only):
 * <ul>
 *   <li>{@code /studies/{uid}[/...]} where uid is granted</li>
 *   <li>{@code /studies?StudyInstanceUID=uid[,uid...]} (QIDO) where every uid is granted</li>
 *   <li>{@code /studies?...} without a UID filter (e.g. the viewer's "other studies for this
 *       patient" query): forwarded only after narrowing to the granted UIDs, so nothing outside
 *       the grant can ever be returned</li>
 * </ul>
 * Everything else (root /series or /instances, the numeric 0020000D alias that could bypass the
 * narrowing, path tricks) is refused.
 */
public final class DicomWebAccessPolicy {

    private static final Pattern STUDY_PATH = Pattern.compile("^/studies/([0-9.]{1,64})(/[A-Za-z0-9./-]*)?$");
    private static final Pattern UID = Pattern.compile("^[0-9.]{1,64}$");
    static final String STUDY_UID_PARAM = "StudyInstanceUID";
    static final String STUDY_UID_TAG = "0020000D";

    /**
     * @param uids          studies the forwarded request is restricted to
     * @param narrowedQuery if present, the query string to forward instead of the original
     */
    public record Decision(List<String> uids, Optional<String> narrowedQuery) {
    }

    private DicomWebAccessPolicy() {
    }

    public static Optional<Decision> authorize(String path, Map<String, String[]> params, String rawQuery,
                                               Set<String> granted) {
        if (path == null || path.contains("..") || path.contains("//") || granted.isEmpty()) {
            return Optional.empty();
        }
        if (params.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(STUDY_UID_TAG))) {
            return Optional.empty();
        }
        Matcher m = STUDY_PATH.matcher(path);
        if (m.matches()) {
            return granted.contains(m.group(1)) ? Optional.of(new Decision(List.of(m.group(1)), Optional.empty()))
                    : Optional.empty();
        }
        if (!"/studies".equals(path)) {
            return Optional.empty();
        }
        String[] values = params.get(STUDY_UID_PARAM);
        if (values == null || values.length == 0) {
            List<String> all = granted.stream().sorted().toList();
            String filter = STUDY_UID_PARAM + "=" + String.join(",", all);
            String q = rawQuery == null || rawQuery.isBlank() ? filter : rawQuery + "&" + filter;
            return Optional.of(new Decision(all, Optional.of(q)));
        }
        List<String> uids = Arrays.stream(values)
                .flatMap(v -> Arrays.stream(v.split("[,\\\\]")))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (uids.isEmpty() || !uids.stream().allMatch(u -> UID.matcher(u).matches() && granted.contains(u))) {
            return Optional.empty();
        }
        return Optional.of(new Decision(uids, Optional.empty()));
    }
}
