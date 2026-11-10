package app.movy.shell;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure bookkeeping of "which source has painted" for the TV start screen, and the rule that decides whose paint a report is.
 * A paint can arrive before the start screen exists (during the splash) or while another source is focused; it is kept until the
 * next real load so the screen can ask for it later instead of waiting for a paint that already happened.
 */
final class PaintLedger {
    private final Set<String> painted = new HashSet<>();

    /** A real load of another page starts: whatever painted before belongs to a document that is going away. */
    void loadRequested() {
        painted.clear();
    }

    void painted(String sourceId) {
        if (sourceId != null) painted.add(sourceId);
    }

    boolean hasPainted(String sourceId) {
        return sourceId != null && painted.contains(sourceId);
    }

    /**
     * The source a paint report counts for: the source of the URL the reporting document says it is, but only while that is
     * also the source of the page the app is showing now. A late report of the previous document (the app's page URL has already
     * moved on) and a report from a frame that is not a source page (an ad) both return null.
     */
    static String accept(LiveConfig cfg, String reportedUrl, String currentPageUrl) {
        Source from = cfg.sourceForUrl(reportedUrl);
        Source now = cfg.sourceForUrl(currentPageUrl);
        return from != null && now != null && from.id.equals(now.id) ? from.id : null;
    }
}
