import { z } from 'zod';

/** A CSS selector (validated for length only; the page engine ignores selectors the browser rejects). */
const selector = z.string().min(1).max(400);
const selectors = z.array(selector).max(40);

const serversSchema = z
  .object({
    toggle: selector.optional().describe('Button that opens the site server picker.'),
    list: selector.optional().describe('Container of the server items.'),
    item: selector.optional().describe('One server entry (clickable).'),
    active: selector.optional().describe('The selected server entry.'),
    failed: selector.optional().describe('Entry or banner shown when a server is dead.'),
    frame: selector.optional().describe('Iframe holding the picker when it is not in the page itself.'),
  })
  .strict();

const episodesSchema = z
  .object({
    list: selector.optional(),
    item: selector.optional(),
    next: selector.optional().describe('Next-episode button.'),
  })
  .strict();

const menuCloseSchema = z
  .object({
    mode: z.enum(['escape', 'toggle', 'click']).describe('escape: Escape key; toggle: click the opener again; click: click `selector`.'),
    selector: selector.optional(),
  })
  .strict();

const railInsetSchema = z
  .object({
    selector: selector,
    prop: z.enum(['padding-left', 'margin-left', 'left']),
  })
  .strict();

const cardActivateSchema = z
  .object({
    card: selector,
    target: selector,
  })
  .strict();

const shellSchema = z
  .object({
    routes: z.array(z.string().regex(/^\/[^?]*$/).max(120)).min(1).max(12).describe('Shell routes (paths, no query): home and the top navigation sections. Served from the local snapshot first.'),
    volatile: selectors.optional().describe('Regexes removed from the HTML before hashing (data that changes on every request).'),
    root: selectors.optional().describe('Selectors of the app root; the boot check needs one of them to hold content within 4 s.'),
    html: selectors.optional().describe('Markers ("#id", ".class" or a tag) a snapshot document must contain.'),
    raceMs: z.number().int().min(100).max(3000).optional().describe('How long the live site may take to beat the local copy. Default 700.'),
    maxAgeHours: z.number().int().min(1).max(72).optional().describe('A snapshot older than this is never served alone. Default 12 (6 for pages that embed data).'),
    keepVersions: z.number().int().min(1).max(5).optional().describe('Versions kept per route (current, previous, older). Default 3.'),
  })
  .strict();

const playerSchema = z
  .object({
    frame: selector.optional().describe('Player iframe or container.'),
    video: selector.optional().describe('The <video> element (same-origin).'),
    root: selector.optional().describe('Player root element.'),
    fullscreenTarget: selector.optional().describe('Element that receives requestFullscreen().'),
    fullscreenButton: selectors.optional().describe("The site's own fullscreen toggles, hidden with CSS on TV."),
    controls: selectors.optional().describe('Focusable player bar controls.'),
    menu: selectors.optional().describe('The open player menu/popover container (visible = open).'),
    hide: selectors.optional().describe('Player bars faded after 5 s idle.'),
    toggles: selectors.optional().describe('Buttons that open a player menu (quality, subtitles, servers, speed).'),
    menuSelected: selectors.optional().describe('The currently selected row of an open menu (focus lands here when it opens).'),
    seekBar: selectors.optional().describe('The progress / seek bar: told the pointer left after a wake, so no hover preview stays up.'),
    menuItems: selectors.optional().describe('Selectable rows inside an open menu (default: buttons and role=option items).'),
    menuColumns: selectors.optional().describe('Column containers of a menu: LEFT/RIGHT jump between them.'),
    menuDrill: selectors.optional().describe('Menu rows that open a sub-page instead of choosing a value: the menu stays open after OK.'),
    menuClose: menuCloseSchema.optional().describe('How an open menu is closed.'),
    menuConfirm: selector.optional().describe('Apply button pressed after an item was chosen (menus that need it).'),
    never: selectors.optional().describe('Never focusable inside the player (text inputs, full-cover buttons, hidden legacy panels).'),
    subtitles: selectors.optional().describe('Subtitle overlay kept visible when the bars hide.'),
    overlayKill: selectors.optional().describe('Intro layers and ad overlays in front of the player, hidden.'),
    closeButton: selector.optional().describe("The site's own close button of an in-page player overlay (BACK clicks it when leaving the player; default is history back)."),
    docMarkers: selectors.optional().describe('A sub-frame holding one of these is a player frame and gets the TV scripts (frames with a <video> or of player size already do).'),
    depth: z.number().int().min(0).max(4).optional().describe('Iframe nesting depth of the real <video> (0 = same document).'),
  })
  .strict();

const loadingSchema = z
  .object({
    hide: selectors.optional().describe('Site spinners and skeletons hidden (visibility) because the app draws the one loading spinner.'),
  })
  .strict();

export const drawerIcons = ['search', 'home', 'film', 'tv', 'sparkles', 'bookmark', 'settings', 'user', 'users', 'history', 'clip', 'flame', 'play'];

const drawerItemSchema = z
  .object({
    id: z.string().regex(/^[a-z0-9-]{1,24}$/).describe('Stable id. Order is fixed by the app: search, home, movies, shows, extras, my list, history, recents, settings, account. "settings" is always the native Settings sheet and is ignored here.'),
    label: z.string().min(1).max(24),
    icon: z.enum(drawerIcons),
    path: z.string().regex(/^\//).max(120).optional().describe('Site route opened inside the SPA (also marks the item current while the page path starts with it).'),
    action: z.enum(['search', 'pick', 'account', 'section']).optional().describe('search: the site search; pick: open a menu element and press an entry in it; account: open the sign-in page and press its button; section: open `path`, scroll to the heading matching `openText` and focus its first tile.'),
    open: selector.optional().describe('Element that opens the menu (pick) or the elements searched by openText.'),
    openText: selector.optional().describe('Regex the opener text must match (case-insensitive).'),
    item: selector.optional().describe('Regex of the menu entry pressed after opening (case-insensitive).'),
    itemSel: selector.optional().describe('Selector of the menu entries to search.'),
  })
  .strict();

const drawerSchema = z
  .object({
    accent: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional().describe('Colour that marks focus and the current source in the drawer.'),
    brand: z.string().min(1).max(40).optional().describe('The site name: a page titled with just this is never listed as a recent title.'),
    modalTitles: z.boolean().optional().describe('The site shows details in a modal without a URL: remember the modal titles as recents.'),
    items: z.array(drawerItemSchema).max(16).describe('Drawer items of this source (the Sources row and Settings are added by the app).'),
  })
  .strict();

const apiField = z.string().regex(/^[A-Za-z0-9_]{1,40}$/);
const searchApiSchema = z
  .object({
    url: z
      .string()
      .max(400)
      .regex(/^(\/|https:\/\/)\S*\{q\}\S*$/)
      .describe('Request template with {q}: a path on the source origin, or an absolute https URL. Lives only in the git-ignored config.'),
    items: z.string().regex(/^[A-Za-z0-9_]{1,40}(\.[A-Za-z0-9_]{1,40}){0,3}$/).optional().describe('Dot path to the result list (default "results").'),
    id: apiField.optional(),
    title: z.array(apiField).min(1).max(6).optional().describe('Fields tried in order for the title.'),
    year: z.array(apiField).min(1).max(6).optional().describe('Fields tried in order; the first 4-digit year is used.'),
    poster: apiField.optional(),
    posterBase: z.string().regex(/^https:\/\/\S+$/).max(200).optional().describe('Prefix for relative poster paths.'),
    type: apiField.optional().describe('Field holding the result type; types missing from `open` (people) are dropped.'),
    open: z
      .record(z.string().regex(/^[A-Za-z0-9_*-]{1,20}$/), z.string().regex(/^\/\S*$/).max(120))
      .refine((o) => Object.keys(o).length >= 1 && Object.keys(o).length <= 8, '1 to 8 routes')
      .describe('Route per type ("*" when there is no type field) with {id}, {slug}, {year}.'),
    cookies: z.boolean().optional().describe('Send the source cookies (same-origin APIs).'),
  })
  .strict();

const searchSchema = z
  .object({
    api: searchApiSchema.optional().describe('JSON search used by the global search across sources (TV).'),
    input: selector.describe('The site search text box. OK on it opens the TV keyboard (a native proxy mirrors the typing into it); also where the in-page mic types.'),
    open: selector.optional().describe('Button that reveals the search box (a header search button) when it is not on the page yet.'),
    route: z.string().regex(/^\//).max(120).optional().describe('Search page route used when neither the box nor the opener is on the page.'),
    results: selector.optional().describe('Result links or cards; the first one gets the focus after a spoken search.'),
    enter: z.boolean().optional().describe('Press Enter in the box after a spoken search (the site needs a submit).'),
    css: z
      .string()
      .max(600)
      .refine((c) => !/[<]|url\(|@import|expression\(/i.test(c), 'no markup, url() or @import')
      .optional()
      .describe('Layout fix for a search box that collapses to zero width without focus (the native keyboard keeps the page input unfocused).'),
  })
  .strict();

export const tvProfileSchema = z
  .object({
    notes: z.string().max(2000).optional().describe('Free text for humans; ignored by the app.'),
    header: selectors.optional().describe('Hidden after 5 s idle, shown on input.'),
    bottomBar: selectors.optional().describe('Bottom bars hidden on idle.'),
    rows: selectors.optional().describe('Horizontal carousels (LEFT/RIGHT inside, UP/DOWN between rows).'),
    cards: selectors.optional().describe('Cards inside rows.'),
    focusables: selectors.optional().describe('Extra elements made focusable.'),
    skip: selectors.optional().describe('Never focused (rail arrows, dots, floating buttons).'),
    firstFocus: selectors.optional().describe('First focus on page load (hero Play), in priority order.'),
    playButtons: selectors.optional().describe('Elements that start playback: trigger auto-fullscreen.'),
    servers: serversSchema.optional(),
    episodes: episodesSchema.optional(),
    player: playerSchema.optional(),
    railInset: z.array(railInsetSchema).max(20).optional().describe('Page wrappers pushed right while the TV mini rail is visible, so no content sits under it.'),
    cardActivate: z.array(cardActivateSchema).max(8).optional().describe('OK on a matching card opens it, then presses the target (e.g. the inner Play of an expanded preview) instead of stopping at the preview.'),
    shell: shellSchema.optional().describe('Versioned local copy of the shell routes, checked against the live site in the background.'),
    loading: loadingSchema.optional(),
    drawer: drawerSchema.optional().describe('The native TV drawer of this source.'),
    search: searchSchema.optional().describe('The site search box (native keyboard proxy, in-page mic, drawer Search).'),
    killOnTv: selectors.optional().describe('Heavy decorative media removed on TV (autoplaying trailer iframes).'),
    viewportWidth: z.number().int().min(320).max(3840).optional().describe('CSS px layout width on TV; default 1280, a site may opt out with 960.'),
  })
  .strict();

const sourceId = z.string().regex(/^[a-z0-9-]+$/);

/** tvProfiles: record of source id -> profile. */
export const tvProfilesSchema = z.record(sourceId, tvProfileSchema);

/** Merges a remote override over base profiles per source: the remote wins per key. Pure; returns new objects. */
export function mergeTvProfiles(base = {}, remote = {}) {
  const out = { ...base };
  for (const [id, prof] of Object.entries(remote)) out[id] = { ...(base[id] ?? {}), ...prof };
  return out;
}

export const cacheSchema = z
  .object({
    enabled: z.boolean().optional().describe('Default: on for the tv target, off elsewhere.'),
    maxMb: z.number().int().min(10).max(2000).default(300),
    maxAgeDays: z.number().int().min(1).max(365).default(30),
  })
  .strict();
