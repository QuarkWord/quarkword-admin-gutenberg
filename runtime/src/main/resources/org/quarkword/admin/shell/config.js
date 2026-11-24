// SPDX-License-Identifier: GPL-2.0-or-later
// The shell's settings: the server's answer, completed with the URLs of the site this page belongs to. Every site of a
// network serves the same pages, so the site is read from the page's own address: whatever precedes the admin path.

/**
 * Loads `quarkword-admin/config.json` next to the page and computes the site's URLs from the page's address.
 *
 * @returns {Promise<{adminPath: string, adminUrl: string, siteRoot: string, restRoot: string,
 *     oidc: {authority: string, clientId: string, scope: string}, lockRefreshInterval: number, wordpress: string}>}
 */
export async function loadConfig() {
  const response = await fetch(new URL("quarkword-admin/config.json", document.baseURI), {
    headers: { Accept: "application/json" },
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw new Error(`The admin settings answered ${response.status}.`);
  }
  const answer = await response.json();
  return siteUrls(answer, location);
}

/**
 * Whether `address` (resolved against `adminUrl`) is a page of the admin folder itself: the same origin, no user name
 * or password, below the admin folder, no further folder. Only such an address keeps the page's relative URLs
 * resolving the same way.
 */
export function sameFolder(address, adminUrl) {
  try {
    const folder = new URL(adminUrl);
    const url = new URL(address, adminUrl);
    const rest = url.pathname.slice(folder.pathname.length);
    return (
      url.origin === folder.origin &&
      url.username === "" &&
      url.password === "" &&
      url.pathname.startsWith(folder.pathname) &&
      !rest.includes("/")
    );
  } catch (notAnAddress) {
    return false;
  }
}

/** The server's answer plus `siteRoot`, `adminUrl` and `restRoot` for a page at `where` (a `Location` or `URL`). */
export function siteUrls(answer, where) {
  const path = where.pathname;
  const at = path.lastIndexOf(answer.adminPath);
  if (at < 0) {
    throw new Error(`This page is not below the admin path ${answer.adminPath}.`);
  }
  const siteRoot = where.origin + path.slice(0, at);
  return {
    adminPath: answer.adminPath,
    adminUrl: siteRoot + answer.adminPath,
    siteRoot,
    restRoot: siteRoot + answer.restPath + "/",
    oidc: answer.oidc,
    lockRefreshInterval: answer.lockRefreshInterval,
    wordpress: answer.wordpress,
  };
}
