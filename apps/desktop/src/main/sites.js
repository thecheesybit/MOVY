'use strict';
// Allowed-site queries for the modules that only need to ask "is this host ours?" (adblock, permissions, cookie
// store). Backed by the live NavRules, so remote config changes and learned mirrors apply immediately.
const { parseParts } = require('../core/url-parts');

/** @param {import('../core/nav-rules').NavRules} rules */
function createSites(rules) {
  return {
    /** True when `url` is http(s) and its host is allowed. */
    isAllowed(url) {
      const p = parseParts(url);
      return p.isWeb && rules.isAllowedHost(p.host);
    },
    /** Same check for a bare host name (permission origins, cookie domains). */
    isAllowedHost: (host) => rules.isAllowedHost(host),
  };
}

module.exports = { createSites };
