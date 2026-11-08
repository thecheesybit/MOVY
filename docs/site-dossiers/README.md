# Site dossiers: cross-site findings

Four in-scope sources (7Movies, Movy, Cinejoy, Moonflix), studied live at 1920x1080. YouTube-based sources are out of scope. Source URLs are deliberately omitted (see config/apps/movy.json, git-ignored).

## What is shared by all sites
1. **Viewport**: a 1080p TV WebView typically reports about 960x540 CSS px at density 2. Cinejoy then shows its phone layout (floating bottom pill nav over content). Fix once in the shell: force a 1920x1080 CSS viewport at density 1 for all sites. Verify per site.
2. **Popups and overlays**: every site tries to open ad popups or draws a fake overlay (7Movies: fake "security check" and fake "is your browser Firefox" srcdoc iframes; Movy: full-viewport srcdoc ad iframe; Moonflix: new tab on first Play click). Block at three levels: network list, `onCreateWindow` deny, and removal of `iframe[srcdoc][sandbox*=allow-modals]` and fixed full-viewport foreign iframes.
3. **Input**: none of the sites supports a D-pad. Spatial focus needs a per-site allowlist of focusables (and a skip list) as proposed in each dossier.

## Player type decides the control strategy
| Site | Player | Control from our adapter |
|---|---|---|
| Movy | in-page `#vp-shell`, same-origin `<video>` | full (direct video API + site controls) |
| Cinejoy | in-page `.player-root`, same-origin `<video>`, labelled buttons | full |
| 7Movies | cross-origin embed iframe in a full-viewport frame | limited (fullscreen the parent, tap into iframe) |
| Moonflix | cross-origin player iframe on its own subdomain | limited (same) |

Build and test order follows this table: Movy and Cinejoy first (full control), then 7Movies and Moonflix.

## Performance
- Cinejoy: about 100 TMDB API calls and about 374 TMDB images per home load. Cache API (stale-while-revalidate) and images in the shell; JS chunks are already immutable-cacheable.
- Moonflix: about 20 tracker hosts. Block them.
- 7Movies: light home, heavy ad hosts on the watch page. Block.
- Movy: about 1.1 MB SPA, normal.

## Not yet studied (all four)
Search UI, TV season/episode pickers, login/My List flows (likely skipped on TV), subtitles.
