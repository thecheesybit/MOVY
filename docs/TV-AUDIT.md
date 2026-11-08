# TV audit (Phase A)

Sony BRAVIA 4K VH22, WebView 156, viewport 960x540 CSS px at DPR 2. Sources are named, never hosted here.
Method: a **debuggable tv build** (same signing key, higher versionCode, `install -r`) and CDP into the TV's WebView
(`webview_devtools_remote_<pid>` forwarded over adb). Early DOM probing of the player/server markup also used a PC headless
Chrome with the TV user agent (the user later asked for TV-only testing; the cache, storage and key-walk data at the end of each
section were re-taken on the TV). Draft profiles: `config/apps/movy.tvprofiles.draft.json` (git-ignored; the config schema is
`.strict()`, so `tvProfiles` cannot yet live in `movy.json`).

Legend for gaps: **B** blocker, **M** major, **m** minor. "tvnav" = `packages/inject/tvnav.js` as it behaves today.

## 7Movies
**Home.** Fixed `header.topbar` (logo, pill nav Home/Movies/TV/Discover/My List, Discord, Search, Settings) plus an "Ads" pill
(`div.pref-pill-wrap`). Hero (`section.hero`, Play/More info, 5 tiny dots) then `section.rail` rows (`div.card-row` of
`div.media-card[role=button][data-media-card]`, each row has `button.rail-arrow` left/right and a `a.rail-hub-link` title link),
a "continue watching" row of 380x214 cards, footer. A skeleton/fade-in runs ~1 s after load.
tvnav today: first DOWN focuses the **Home tab** (not hero Play); cards, arrows, hub links, tabs all focusable. Unreachable: hero
dots (9x9, under the 14 px threshold). Problems: UP from a poster row lands on the row's own arrow, then on the header, skipping
the previous row (seen on the TV: UP from a Drama row went to Settings). Arrows are pointless focus stops. Per-card
watchlist buttons (`button.card-watchlist`) double the stops on continue-watching.
**Title page** (`/movie/<id>`, `/tv/<id>`): full-height hero with an autoplaying muted **YouTube trailer iframe**
(`.dp-hero-trailer iframe`, cross-origin; heavy for a 32-bit TV), Play (`a.btn-primary`, 522 px down so below the fold at 540),
list/share buttons, cast, related. TV shows: native `<select>` for seasons and `article.unified-episode-card[data-navigation-href]`
(role=button, 6-8 per season, "Up next" marked `.selected`). On the TV the hero Play goes **straight to `/…/watch`**.
**Server picker.** In the parent page, an overlay above the player (`.unified-watch-overlays` z=26): chip
`button.unified-server-toggle` "Servers & Mode" -> `.unified-server-control` with two `<details class=unified-server-dropdown>`:
**Mode** (Direct, Embed) and **Server** (BigBang, Orion, Vela, Titan, Atlas, Lyra = 6). Click-open, not hover. Items are
`.unified-server-menu button`, active `.selected`, **dead servers get `.is-failed`** (seen on the TV). Not in the iframe.
**Player.** Cross-origin iframe (`.unified-player-frame > iframe`, url carries `fs=parent&seek=10&autonext=1&autoskip=0`), player
is hls.js with a custom UI (`div#player.layout-labeled`, ids `#lbBack #lbQuality #lbSubs #centerToggle #playBtn #rewindBtn
#forwardBtn #muteBtn #timeLabel #lbFs #lb_lockBtn`, `#episodesPanel`, `#nextUpCard`, `#introSkip`, `#fatalReload`). Autoplays.
Layers in front: `#loader` (poster + title + progress "BUFFERING"), then the title card. In-stream burned-in ad text on some
sources (content, not DOM). Ads/scams (PC without adblock): `body > iframe` fixed 100% (no src, z-index 2147463647, QR "Confirm
you're not a robot" + "Game for Cash" dialog) and an inline `div.notranslate` srcdoc iframe; on the TV adblock + scamguard left
the page clean.
**Search.** Search button opens an in-page overlay (route unchanged) with an auto-focused `input[type=search]` and a trending
grid (`div.search-poster-card`) that the site itself navigates with arrows (sets `.active`, so tvnav draws no ring there).
**TV gaps.** M: first focus is the header; UP between rows skips; rail arrows are noise. M: tvnav focuses the big player iframe
on the first DOWN, never the "Servers & Mode" chip; the embed's own tvnav then picks the full-frame `#centerToggle`; reaching the chip
takes UP out of the embed top bar (works, via the `__movyTvExit` message). **M: items inside a closed `<details>` get focused
while invisible** (seen on the TV). m: hero dots, title page Play below the fold, native season `<select>`.

## Movy
**Home.** `header` fixed (logo, Home/Movies/Shows/Anime, search, login). Hero `[class*=HomeBanner]` auto-rotating 3-layer slider
(`slideLayer`, off-screen layers are `aria-hidden=true`; current `slideCurrent`), pause button, 5 markers. Rows `ScrollRow`
(`__track` is a **tabindex scroll container**, `__arrow` buttons), cards `a[href^=/movie/|/tv/]`, a streaming-service rail, genre cards.
tvnav on the TV: first DOWN -> the logo; hero Play reached after a second DOWN but it belongs to the *current* slide and goes stale as it
rotates; the whole `__track` takes focus as one giant ring, **OK on it does nothing** (screenshot `w12_tv_movy_title.png`); UP from
the first row goes to the header tab, not the hero. Title page vote buttons (tiny) sit between Back and Play.
**Title.** `/movie/<id>`, `/tv/<id>`; Play `button.mv-btn--primary` (also `?play=true` links), Add to List, Download; TV shows
have a season dropdown (`button` "Season N"), episode `a[href*="/tv/ID/S/E?play=true"]` list/grid. An ad iframe (`div.notranslate`) can sit
over the Download button when adblock is off.
**Server picker.** Inside the player, behind the **Quality** button (`#vp-shell .tabbable`, top bar): a click-open popover
`.player-surface` with columns QUALITY (2160/1080/720/480) and **SERVER (18: Miami, Boise, Nashville, Chicago, Vegas, Reno, Oakland,
Seattle, Phoenix, Atlanta, Portland, Austin, Dallas, Munich, Berlin, Paris, Delhi, Cancun)** with a favourite heart; active row has
`bg-white/[0.14]`. Same document, no iframe.
**Player.** In-page custom player (`#vp-shell > .vp-root`), blob/MSE video, no library globals. Top: back, title, Quality, Audio &
Subtitles, Next Episode; bottom: `#ButtonPlay`, -10, +10, `#ButtonVolume`, "Episodes" panel, `#ButtonFullscreen`. Autoplays on Play. It is
`position:fixed; inset:0`, so it looks fullscreen but `document.fullscreenElement` stays null. Intro = the stream's own studio logos.
Ads (PC, no adblock): orange floating play bubble with X, bouncing ball widget, QR scam iframe. None on the TV.
**Search.** Header Search button expands an auto-focused `input[type=text]` (placeholder "Search movies, shows, and anime") with tab
buttons All/Movies/Shows/Anime; results are `button.flex.w-full.items-center` rows (a chevron expands actions).
**TV gaps.** M: row tracks take focus instead of cards. M: hero Play unreachable by DOWN from the header; stale on rotation.
M: Quality/servers popover needs the top bar awake: with controls hidden, first keys find nothing (ring null), then focus lands on
the back arrow (an svg), then `#ButtonPlay`. m: vote buttons, logo overlapping the hero title at 960 px.

## Cinejoy
**Home.** At 960 px the layout is the *mobile* one (breakpoint `lg`=1024): a fixed top bar with only the logo, and a **fixed bottom
pill nav** (`div[class*="lg:hidden"] > div.fixed`, 6 icon-only items: Home, Movies, Series, Lists, Search, Settings; no text). Hero
(`div.relative.z-0.h-[85vh]`, itself cursor:pointer), provider tiles (`a[class~="group/tile"]`), 18 rows
`div[class~="group/row"] div.overflow-x-auto` of `a.block` cards (poster 140x256, "View All" links); row arrows are `hidden lg:flex`
so absent. A "Back to top" `button.scroll-to-top-btn` floats at bottom right.
tvnav: first DOWN focuses the whole hero container (not Play); the bottom pill and the floating Back-to-top button steal DOWN/UP
(DOWN from the top landed on the bottom nav). Icon-only nav is unlabeled for the user.
**Title.** `/movie/<id>-<slug>`, `/series/<id>-<slug>`: Play (`button.relative.rounded-full.flex`), add, download, hide; episode
`a.flex.flex-col.gap-3` -> `/watch/tv/<id>/<s>/<e>`; season `button` ("Season 1") and sort button; then cast, a **comments section with ~58
vote and ~29 reply buttons** (huge tab/D-pad sink) and related.
**Server picker.** Player button `aria-label="Servers"` (cloud icon) opens a right-side panel "Servers" with `button.src-row`
(5: Secret, Nebula, Lisbon, Solara, Athens), active row inside `.src-row-wrap.is-active` with a green check. Click-open, same document.
**Player.** `/watch/movie/<id>`: in-page custom player (`div.player-root`), blob/MSE video, no library, no iframe. Controls have
aria-labels (Back, Cast, Seek Video slider, Pause, Rewind 10s, Forward 10s, Mute, Volume slider, Picture in Picture, Servers,
Subtitles, Settings, Fullscreen). Autoplays; no intro layer. D-pad: all controls reachable once awake; **the Volume range input
traps LEFT/RIGHT** (they change volume), only UP/DOWN leave it. Ads: none seen. Some titles show the site's own "Server error"
in the player (W11 note).
**Search.** Bottom-nav search button -> `/search` route, auto-focused `input` ("Search movies, TV shows & people...").
**TV gaps.** M: hero container is the first focus; floating buttons and bottom pill intercept vertical nav; comment sink on title
pages. M: volume slider trap. m: icon-only nav.

## Moonflix
**Home.** Start URL redirects to `/browse`. `header.fixed` (logo, Home/Movies/TV Shows/New & Popular/My List, search, AI Search, API, bell with badge, profile).
Hero is a full-screen trailer (`div.relative.h-screen.overflow-hidden`, Play, More Info, Unmute). Rows are
`[role=region][aria-label][tabindex=0]` (12+ rows, 10-28 cards each, `div.cursor-pointer` cards, no anchors; hover preview with 28 px Play /
Add / More-info buttons; "Top 10" rows with a big scroll button). Footer links.
tvnav on the TV: first DOWN -> logo; DOWN -> the **row region itself** (a 950x211 ring), then the 28 px hover buttons and the
scroll arrows. **Cards are unreachable** (inside a native-focusable `tabindex=0` row, so the cursor:pointer heuristic skips them): B.
**Title.** Card click opens a Netflix-style **modal** (route stays `/browse`; `div.fixed.z-[300]`, close X, Play, add, like, mute, tabs
Episodes / More Like This / Details, a "Season N" dropdown and episode rows `div.group.flex.gap-4.p-4`, "More" expanders).
**Server picker.** In the player, two layers: wrapper-frame button `#servers-button` / floating chip `button.server-frame-choose`
("Choose servers") opens `#server-picker-menu.embed-server-menu` (full overlay): **Moonflix Player (native hls), Nitno, Ranger, Filem,
Hexon (4 embeds + native)**; active has a leading check; `button.embed-server-option`. Click-open, in the middle frame.
**Player.** Three nested documents: page -> `iframe[title="Moonflix player"]` (same-site wrapper, `allow="autoplay; fullscreen"`, ids `#back-button #audio-subtitle-toggle
#speed-button #servers-button #fit-button #fullscreen-button #skip-prompt-btn`, hls.js `video#video` in `#video-container`) -> for embed servers a
cross-origin `iframe.server-frame` (allow fullscreen/PiP/encrypted-media) carrying the stream and its own UI. Autoplay: wrapper autoplays;
the video here starts `paused, duration 0` until the embed loads. Layers: spinner, then the embed; "Skip Intro" button.
**Search.** "Open search" button expands an inline `input[type=search]` ("Titles, people, genres") in the header, results as a dropdown list
(poster, title, year, match%).
**TV gaps.** B: cards unreachable (above). M: 3-deep iframe nesting; focus enters the iframe and then the nested embed
(`iframe.server-frame` becomes the target); the picker menu is reachable only through the wrapper chip. M: hero trailer autoplays (CPU).
m: page scrollbar visible beside the player; header hides at scroll top on PC.

## Remote-control gap list (ranked)
- **B** Moonflix cards unreachable; rows focus as one region.
- **B** Movy row tracks take the ring and OK does nothing (cards are one RIGHT away; first arrival is wrong).
- **M** UP between rows (7Movies) skips rows / lands on arrows; arrows and tiny buttons dilute focus on every site.
- **M** Server pickers are all click-open and live in 4 different places (parent overlay, Quality popover, player panel, wrapper overlay),
  so a native Servers sheet is needed. None is hover-only.
- **M** First focus is a header item (7Movies, Movy, Moonflix) or a huge hero container (Cinejoy), not Play.
- **M** Cross-origin player frames (7Movies embed, Moonflix embed) need a per-frame tvnav and the exit message protocol; controls auto-hide so first keys are lost.
- **M** Hidden `<details>` content (7Movies) and off-screen carousel layers (Movy) can take focus.
- **M** Cinejoy bottom pill + floating Back-to-top + comment sink; volume range traps LEFT/RIGHT.
- **m** Hero dots too small, native season `<select>`, title Play below the fold, icon-only nav, vote buttons.

## Fullscreen (per source)
| Source | Own fullscreen button (frame) | Programmatic entry | Hide it with CSS? |
|---|---|---|---|
| 7Movies | `#lbFs` in the **embed frame**; because the embed url has `fs=parent`, a click posts to the parent which calls `requestFullscreen` on `div.unified-player-frame` | call `requestFullscreen()` on `div.unified-player-frame` from the parent (the embed iframe has `allow=fullscreen`) | Yes (`#lbFs{display:none}` in the embed frame), no other code path needs it observed |
| Movy | `#ButtonFullscreen` (`#vp-shell`, main frame); fullscreen target observed: `#vp-shell` | `#vp-shell.requestFullscreen()`; player is already `position:fixed;inset:0`; the 'f' key did **not** trigger it | Yes, no key/dblclick dependency found |
| Cinejoy | `button[aria-label="Fullscreen"]` (main frame) -> `.player-root` | `.player-root.requestFullscreen()`; the **'f' key also works** (document keydown) so hide the button but keep the key; double-click on the root also fired it | Yes, keyboard 'f' stays as an API |
| Moonflix | `#fullscreen-button` in the wrapper frame -> `#video-container` (native mode); for embed servers the nested embed has its own button | in embed mode call `requestFullscreen()` on `iframe.server-frame` (inside wrapper) or on the outer `iframe[title="Moonflix player"]`; both iframes have `allow=fullscreen` | Wrapper button yes; the embed's own button is cross-origin, so hide via the embed frame's tvnav instance |

All calls need a user gesture; the OK key handler counts. Not tested without a gesture.

**What leaves fullscreen by itself** (analysis from the code paths; the autonext/server-switch ones are unverified on the TV):
7Movies: autonext (`autonext=1`) reloads the embed with a new episode, a server/mode switch rebuilds `.unified-player-frame`'s iframe, the
`fs=parent` handshake re-requests on the parent; Movy: in-page, so episode/server switch re-mounts `#vp-shell`; Cinejoy: route change to the next
`/watch/tv/<id>/<s>/<e>` re-renders `.player-root` (fullscreen element removed, so it exits); Moonflix: switching server swaps `iframe.server-frame`
(element removed -> exit) and "Moonflix Player" switch re-creates `#video-container`. General: any removal of the fullscreen element, orientation/resize (not on TV),
scam/popup iframes taking fullscreen, and the WebView's own guard (W8) re-applying. Policy: re-request fullscreen on the new container right after such a swap
(first key or in the `playing` handler).

## Cacheability (TV, CDP `Network.responseReceived`, cache disabled, home page)
| Source | Findings |
|---|---|
| 7Movies | Own origin: JS/CSS hashed, `public, max-age=31536000, immutable`, ETag+Last-Modified; fonts immutable. Posters on the TMDB image CDN: `public, max-age~31.9M`, Last-Modified on all, ETag on ~1/3. No Set-Cookie on static assets. API `fetch`: `max-age 0..600`, `s-maxage 3600`. |
| Movy | Own origin: 51 hashed JS immutable, CSS/font immutable; images via the site's image proxy (`w1920-q80.webp`): 9 immutable / 14 `max-age=14400`; i18n JSON `max-age=14400`. No Set-Cookie. Third-party tracker scripts `no-cache`. |
| Cinejoy | Own JS/CSS: hashed, immutable (78 JS + 13 CSS), **all carry Set-Cookie** (CDN challenge cookies), so a shared HTTP cache must ignore/strip it. Logo `max-age=86400, must-revalidate`. Posters load lazily (not in the first-paint sample). |
| Moonflix | Own JS/CSS/images: `max-age=43200`, ETag+LM, **filenames hashed** (`index-XsMk….css`) for most; fonts immutable. Posters from the TMDB CDN (`max-age~31.9M`); API JSON `max-age` 137..86400 with ETag. No Set-Cookie on statics. |
All four load posters from either the TMDB image CDN or an image proxy with long freshness: a disk cache keyed by URL for image/font/hashed static
is safe; skip API fetches and anything with Set-Cookie semantics.

## Viewing-flow data
- **7Movies:** episodes: `article.unified-episode-card` (`data-navigation-href="/tv/<id>/watch?season=&episode="`) + `select` for seasons on the title page; inside the
  embed `#episodesPanel`, `#lbNext`, `#nextUpCard` (autonext). Resume/continue: **localStorage** (`cinrift_continue_watching`, `cinrift_episode_progress`,
  `cinrift_player_source`; account flag `cinrift_signed_in`); a "continue watching" home row. Dead server: server button gets `.is-failed`;
  embed `#fatalReload`/`#loader`.
- **Movy:** episodes in the title page list and the player's "Episodes" panel plus "Next Episode" (top bar). No local progress (only `app_search_history`);
  resume appears to be account-based (Login). Dead server: not observed; treat "no `playing` within N s" as failed.
- **Cinejoy:** episodes on `/series/…` cards and `/watch/tv/<id>/<s>/<e>`; resume in localStorage (`Cinejoy::Progress`, `cinejoy_player_local`), lists order key. Dead
  server: the site's own "Server error" text in the player (W11), so also check for the absence of `playing`.
- **Moonflix:** episodes in the modal (Episodes tab, Season dropdown) and `/tv/<id>/S1/E1` in the wrapper; **no localStorage resume** (only analytics), My List/profile
  server-side. Dead server: embed is cross-origin, so only "no `playing`/`timeupdate` from the wrapper `video#video`" plus the visible spinner can be observed; the
  server menu shows a check on the active one.

## Cross-site summary
Common: TMDB-driven catalogues and artwork; a full-width hero; horizontal rows of poster cards; click-open (never hover-only) server pickers with 5-18 named servers;
hls/MSE players with custom UIs; autoplay on Play; adblock+scamguard keep the TV pages clean. Generic engine can handle: spatial navigation over
`a`/`button`/`[role=button]`, row-aware LEFT/RIGHT, fullscreen on a container, idle hide, per-frame instances and the exit message. Needs per-site profile data: which
selectors are rows/cards/skip (Moonflix `[role=region]` rows, Movy `__track`, 7Movies rail arrows, Cinejoy hero container), the header and bottom bar to hide on idle,
play buttons, the server picker (toggle/list/item/active, 4 different shapes, 7Movies' `<details>`), the fullscreen target and its own button, iframe depth, and
overlay kill lists. **Not audited:** system bars/Google TV overlay (none appeared in any screenshot or key walk); cold-start fullscreen without a gesture; real
episode autonext behaviour; Cinejoy and Moonflix full key walks on the TV beyond home (their player/title keys were probed with tvnav on a PC Chrome first).
