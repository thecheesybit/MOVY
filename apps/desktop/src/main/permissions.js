'use strict';
const { parseUrl } = require('./hosts');

// Everything not listed is denied (camera, microphone, geolocation, usb, hid, ...).
const ANYWHERE = new Set(['fullscreen', 'clipboard-sanitized-write']);
const ALLOWED_SITES_ONLY = new Set(['notifications']);

function decide(permission, requestingUrl, sites) {
  if (ANYWHERE.has(permission)) return true;
  if (ALLOWED_SITES_ONLY.has(permission)) {
    const u = parseUrl(requestingUrl);
    return !!u && sites.isAllowedHost(u.hostname);
  }
  return false;
}

function installPermissions(ses, sites) {
  ses.setPermissionRequestHandler((_wc, permission, callback, details) => {
    callback(decide(permission, details.requestingUrl, sites));
  });
  ses.setPermissionCheckHandler((_wc, permission, requestingOrigin) => decide(permission, requestingOrigin, sites));
  ses.setDevicePermissionHandler(() => false);
}

module.exports = { installPermissions, decide };
