# Round 5 — Level 1 code review (commit 7a8b13c)

Read-only review, 2026-11-10. **Status: fixed in "Round 5 - Review fixes" (see REPORT.md); #9 skipped, #3 partly fixed.**

| # | Sev | Where | Problem | Fix | Status |
|---|---|---|---|---|---|
| 1 | HIGH | `LaunchController.onPagePainted` (~162) | A paint that arrives before StartView exists (during the splash) is dropped, and `requestLoad` dedup (A→B→A) gets no new paint, so the ring waits for the 15 s `paintCap` | Keep the set of painted source ids; in the dedup branch and right after creating StartView, call `startView.onPagePainted(id)` if it already painted; clear on a real reload | **FIXED: `PaintLedger` keeps painted ids; checked on StartView creation and in the dedup branch; cleared on a real load.** |
| 2 | MED | `MainActivity` (~366) + `tvpaint.js` | A late paint from the old document is credited to the new source (pageUrl overwritten at navigation start) | `painted(url)` checked against the current page | **FIXED: the page sends its own URL (`painted(url)`); `PaintLedger.accept` credits it only if it is the same source as the page now showing.** |
| 3 | MED | `TvBridge.painted/buffering` (~95-110) | Callable from any frame, including ad iframes: they can lift the cover or pin the spinner on | Top frame only, or a per-document token; cap the buffering set | **PARTLY FIXED: paint reports are checked as in #2 (an ad frame is not a source page); buffering set capped (8 players, 40-char tokens, 60 s TTL). No true top-frame check: the bridge cannot tell frames apart and player embeds must report.** |
| 4 | MED | `MainActivity` (~677) `leavePlayer` | A double BACK inside the 250 ms delay leaves the page entirely | Record the URL at post time and skip if it changed; ignore BACK during the window | **FIXED: `LeavePlayerGate` ignores BACK for 500 ms and skips the delayed leave if the URL changed.** |
| 5 | MED | `tvplayerfx.js` (~99-115) | Zooms cross-origin embeds despite the comment; `normalW` is wrong in late-booted frames | Zoom only when `!inFrame` | **FIXED: `tvplayerfx.js` zooms only when `!inFrame` (`mayZoom`).** |
| 6 | MED | `DohProxy.prewarm` (~106-125) | `q.size()` read outside `synchronized(q)` | Move it inside the lock | **FIXED: `q.size()` read inside `synchronized(q)`.** |
| 7 | MED | `DohProxy.fetch` (~145) | Fair semaphore waits up to 20 s on WebView IO threads, then a hard "busy" error | Wait 5–8 s and fall back to the WebView's own resolution on busy | **FIXED: slot wait 20 s to 6 s, then "busy"; `DohRescue.intercept` returns null so the WebView resolves the host itself.** |
| 8 | LOW | `DohRescue.prewarm()` | No caller | Wire it into `requestLoad` / start-screen focus, or delete it | **FIXED: wired into `LaunchController.requestLoad`.** |
| 9 | LOW | `tvnav.js` 1657 lines, `tvfs.js` 797; `StartView` constructor ~70 lines | Size | Split (L2 shrinks tvnav); extract the column builder | **SKIPPED: risky refactor outside this round; tvnav.js unchanged at 1086, tvfs.js 793.** |
| 10 | LOW | `LoadingUi` (39-40) | Runnables not removed on destroy | Add `release()` | **FIXED: `LoadingUi.release()` called from `onDestroy`.** |
| 11 | LOW | `tvfocusmem.js` (~85) | `setInterval(onRoute, 500)` polls forever | Hook pushState/popstate | **FIXED: pushState/replaceState wrapped, popstate/hashchange; no polling.** |
| 12 | LOW | `tvgate.js` | An invalid marker selector is skipped silently | Warn in debug builds | **FIXED: invalid `docMarkers` selectors dropped one by one and named with `console.warn` in debug builds.** |

Checked OK: the phone flavour is gated (`cfg.tv`/`isTv`/`MOVY_OPTS.tv`); no hosts leaked; no global leaks in tvgate/tvpaint; StartView animators are cancelled on detach.
