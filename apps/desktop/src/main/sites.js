'use strict';
// The ONLY module that knows how the config describes sites (url + allowedHosts today; a list of
// "sources" later). Everything else asks `sites` for the start URL and for allowed-host decisions.
const { hostMatches, isHttp, parseUrl } = require('./hosts');

/** @param {{url:string, allowedHosts?:string[]}} cfg */
function createSites(cfg) {
  const startHost = parseUrl(cfg.url)?.hostname;
  const patterns = [...(cfg.allowedHosts ?? [])];
  if (startHost) patterns.push(startHost); // the start page itself is always allowed

  return {
    startUrl: cfg.url,
    /** True when `url` is http(s) and its host matches an allowed pattern. */
    isAllowed(url) {
      const u = parseUrl(url);
      return !!u && isHttp(url) && patterns.some((p) => hostMatches(p, u.hostname));
    },
    /** Same check for a bare host name (permission origins). */
    isAllowedHost(host) {
      return patterns.some((p) => hostMatches(p, host));
    },
    /** Allowed host patterns, e.g. for filtering cookies to persist. */
    hostPatterns: () => [...patterns],
  };
}

module.exports = { createSites };
