// SPDX-License-Identifier: GPL-2.0-or-later
// The preview session: the site's front end knows who is previewing by a session cookie, since a preview window is a
// plain navigation that carries no access token. The editor opens a draft's preview as the post's own address with
// preview=true and no preview token, and a published post's through its autosave's preview link, whose token is bound
// to the same viewer; both are rendered for the user only while the cookie names them. The shell opens the session
// before the editor mounts and renews it before it expires. It is never closed when the page is left: like
// WordPress's login cookie it outlives the tab, and the next editor page renews it.

/** The share of the session's lifetime after which it is renewed. */
const RENEW_AT = 0.8;

/** How long to wait before trying again when a renewal failed in passing (seconds). */
const RETRY_AFTER = 60;

/** Whether a failure is in passing (no answer, a server error, a timeout, too many requests): worth trying again. */
function passing(failure) {
  const status = failure && failure.status;
  return typeof status !== "number" || status >= 500 || status === 408 || status === 429;
}

/** The longest delay a browser timer takes (about 24.8 days): a session that lives longer is renewed early. */
const LONGEST_DELAY = 2147483647;

/**
 * The preview session of the signed-in user on this site.
 *
 * - `open()` opens a session: `POST quarkpress/v1/preview/session`, whose answer `{expires_in}` comes with the
 *   `HttpOnly` cookie the browser keeps for the site's path; the renewal is then scheduled at 80% of `expires_in`.
 *   Resolves to the answer; rejects with the request's error.
 * - `renew()` opens a new session at once (the scheduled renewal calls it), and schedules the next.
 * - `scheduled()` tells whether a renewal is scheduled.
 *
 * A refusal for want of a session (401, after the request's own token renewal) calls `ctx.onUnauthorized` and stops
 * renewing; any other refusal (403 and the like) stops renewing too; a failure in passing (no answer, 5xx, 408, 429)
 * is retried a minute later.
 *
 * @param {{api: object, onUnauthorized: function(Error): void}} ctx
 */
export function createPreviewSession(ctx) {
  const { api, onUnauthorized } = ctx;
  const path = "quarkpress/v1/preview/session";
  let timer = null;

  function cancel() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function schedule(seconds) {
    cancel();
    const delay = Math.min(Math.max(seconds, 1) * 1000, LONGEST_DELAY);
    timer = setTimeout(() => {
      timer = null;
      renew().catch(() => {});
    }, delay);
  }

  async function open() {
    let answer;
    try {
      answer = await api.fetchJson(path, { method: "POST" });
    } catch (failure) {
      if (failure && failure.status === 401) {
        cancel();
        onUnauthorized(failure);
      } else if (passing(failure)) {
        console.warn("QuarkPress admin: the preview session could not be opened; trying again later.", failure);
        schedule(RETRY_AFTER);
      } else {
        cancel();
        console.warn("QuarkPress admin: the preview session was refused; previews will not show drafts.", failure);
      }
      throw failure;
    }
    const lifetime = Number(answer && answer.expires_in);
    schedule(Number.isFinite(lifetime) && lifetime > 0 ? lifetime * RENEW_AT : RETRY_AFTER);
    return answer;
  }

  function renew() {
    return open();
  }

  function scheduled() {
    return timer !== null;
  }

  return { open, renew, scheduled };
}
