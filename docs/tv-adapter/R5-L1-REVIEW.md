# Round 5 — Level 1 code review (commit 7a8b13c)

Read-only review, 2026-11-10. **Status: open. Fix after L2 lands** (L2 also edits MainActivity/TvBridge).

| # | Sev | Where | Problem | Fix |
|---|---|---|---|---|
| 1 | HIGH | `LaunchController.onPagePainted` (~162) | A paint that arrives before StartView exists (during the splash) is dropped, and `requestLoad` dedup (A→B→A) gets no new paint, so the ring waits for the 15 s `paintCap` | Keep the set of painted source ids; in the dedup branch and right after creating StartView, call `startView.onPagePainted(id)` if it already painted; clear on a real reload |
| 2 | MED | `MainActivity` (~366) + `tvpaint.js` | A late paint from the old document is credited to the new source (pageUrl overwritten at navigation start) | `painted(url)` checked against the current page |
| 3 | MED | `TvBridge.painted/buffering` (~95-110) | Callable from any frame, including ad iframes: they can lift the cover or pin the spinner on | Top frame only, or a per-document token; cap the buffering set |
| 4 | MED | `MainActivity` (~677) `leavePlayer` | A double BACK inside the 250 ms delay leaves the page entirely | Record the URL at post time and skip if it changed; ignore BACK during the window |
| 5 | MED | `tvplayerfx.js` (~99-115) | Zooms cross-origin embeds despite the comment; `normalW` is wrong in late-booted frames | Zoom only when `!inFrame` |
| 6 | MED | `DohProxy.prewarm` (~106-125) | `q.size()` read outside `synchronized(q)` | Move it inside the lock |
| 7 | MED | `DohProxy.fetch` (~145) | Fair semaphore waits up to 20 s on WebView IO threads, then a hard "busy" error | Wait 5–8 s and fall back to the WebView's own resolution on busy |
| 8 | LOW | `DohRescue.prewarm()` | No caller | Wire it into `requestLoad` / start-screen focus, or delete it |
| 9 | LOW | `tvnav.js` 1657 lines, `tvfs.js` 797; `StartView` constructor ~70 lines | Size | Split (L2 shrinks tvnav); extract the column builder |
| 10 | LOW | `LoadingUi` (39-40) | Runnables not removed on destroy | Add `release()` |
| 11 | LOW | `tvfocusmem.js` (~85) | `setInterval(onRoute, 500)` polls forever | Hook pushState/popstate |
| 12 | LOW | `tvgate.js` | An invalid marker selector is skipped silently | Warn in debug builds |

Checked OK: the phone flavour is gated (`cfg.tv`/`isTv`/`MOVY_OPTS.tv`); no hosts leaked; no global leaks in tvgate/tvpaint; StartView animators are cancelled on detach.
