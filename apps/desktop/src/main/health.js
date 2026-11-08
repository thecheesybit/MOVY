'use strict';
// Source health: HEAD (fallback ranged GET) on each enabled source's start URL, in parallel, 5 s timeout, no cookies.
// Only a hint for the Island dots and the failover suggestion. Never logs hosts.
const { net } = require('electron');
const rules = require('../core/health-rules');

const STALE_MS = 60_000;
const TIMEOUT_MS = 5000;

async function probe(url) {
  const t0 = Date.now();
  const attempt = async (method, headers) => {
    const res = await net.fetch(url, { method, headers, credentials: 'omit', redirect: 'follow', signal: AbortSignal.timeout(TIMEOUT_MS) });
    res.body?.cancel().catch(() => {});
    return res.status;
  };
  try {
    let status = await attempt('HEAD', {});
    if (rules.headNeedsFallback(status)) status = await attempt('GET', { Range: 'bytes=0-0' });
    return rules.classify(status, Date.now() - t0);
  } catch {
    return 'down';
  }
}

class SourceHealth {
  constructor(repo) {
    this.repo = repo;
    this.statuses = new Map();
    this.last = 0;
    this.running = false;
    this.listeners = new Set();
  }

  onChange(fn) {
    this.listeners.add(fn);
  }

  status(id) {
    return this.statuses.get(id) ?? 'unknown';
  }

  isChecking() {
    return this.running;
  }

  suggestion(excludeId) {
    return rules.suggest(this.repo.enabledSources(), (id) => this.status(id), excludeId);
  }

  /** Refreshes when the last check is stale. */
  refresh() {
    if (Date.now() - this.last > STALE_MS) this.refreshNow();
  }

  async refreshNow() {
    if (this.running) return;
    this.running = true;
    this.notify();
    try {
      await Promise.all(
        this.repo.enabledSources().map(async (s) => {
          this.statuses.set(s.id, await probe(s.url));
          this.notify();
        }),
      );
      this.last = Date.now();
    } finally {
      this.running = false;
      this.notify();
    }
  }

  notify() {
    for (const fn of this.listeners) fn();
  }
}

module.exports = { SourceHealth };
