// SPDX-License-Identifier: GPL-2.0-or-later
// The post lock, as WordPress's edit screen, heartbeat and admin-ajax handle it for the block editor, through the
// site's lock routes: taken before the editor is mounted (another user's fresh lock shows WordPress's "already being
// edited" dialog, whose "Take over" takes it anyway), refreshed while the tab is visible, released when the page is
// left (with the lock's one-time release token, since a page being left can send no Authorization header). When a
// refresh finds that another user has taken over, the editor autosaves once, saving is frozen and WordPress's
// "taken over" dialog is shown; WordPress itself keeps autosaving on every refusal, the shell does it once.

import { restRoute } from "../api.js";

/** WordPress's lock dialog titles and texts. */
export const LOCKED_TITLE = "This post is already being edited";
export const TAKEN_OVER_TITLE = "Someone else has taken over this post";
const LOCKED_TEXT = " is currently working on this post, which means you cannot make changes, unless you take over.";
const LOCKED_ANONYMOUS =
  "Another user is currently working on this post, which means you cannot make changes, unless you take over.";
const TAKE_OVER_WARNING =
  "If you take over, the other user will lose editing control to the post, but their changes will be saved.";
const TAKEN_OVER_TEXT =
  " now has editing control of this post. Don't worry, your changes up to this moment have been saved.";
const TAKEN_OVER_ANONYMOUS =
  "Another user now has editing control of this post. Don't worry, your changes up to this moment have been saved.";

/** The name of the lock that freezes saving once the post is taken over. */
const FROZEN = "quarkpress-lock";

/** The refresh interval in seconds the configuration names; a minute when it names none that can be used. */
function intervalSeconds(config) {
  const seconds = Number(config.lockRefreshInterval);
  return Number.isFinite(seconds) && seconds >= 1 ? seconds : 60;
}

/** A paragraph whose text starts with the user's name in bold, or the anonymous wording when there is no name. */
function named(name, text, anonymous) {
  if (!name) {
    return paragraph(anonymous);
  }
  const element = document.createElement("p");
  const strong = document.createElement("strong");
  strong.textContent = name;
  element.append(strong, text);
  return element;
}

function paragraph(text) {
  const element = document.createElement("p");
  element.textContent = text;
  return element;
}

/**
 * Shows a lock dialog over the whole page that nothing dismisses: a title, the user's avatar when there is one, the
 * paragraphs and the actions (a button for `onClick`, a link for `href`). Replaces any lock dialog already shown.
 */
function showDialog(title, avatar, paragraphs, actions) {
  removeDialog();
  const overlay = document.createElement("div");
  overlay.id = "qp-lock";
  overlay.className = "qp-overlay";
  const box = document.createElement("div");
  box.className = "qp-overlay-box qp-lock-box";
  box.setAttribute("role", "alertdialog");
  box.setAttribute("aria-modal", "true");
  box.setAttribute("aria-labelledby", "qp-lock-title");
  const heading = document.createElement("h1");
  heading.id = "qp-lock-title";
  heading.textContent = title;
  box.append(heading);
  if (avatar) {
    const image = document.createElement("img");
    image.className = "qp-lock-avatar";
    image.src = avatar;
    image.alt = "";
    image.width = 64;
    image.height = 64;
    box.append(image);
  }
  box.append(...paragraphs);
  const row = document.createElement("div");
  row.className = "qp-lock-actions";
  for (const action of actions) {
    let control;
    if (action.href) {
      control = document.createElement("a");
      control.href = action.href;
      control.className = "button";
    } else {
      control = document.createElement("button");
      control.type = "button";
      control.className = "button button-primary";
      control.addEventListener("click", () => {
        control.disabled = true;
        Promise.resolve(action.onClick()).catch((failure) => {
          control.disabled = false;
          console.warn("QuarkPress admin: the action failed.", failure);
        });
      });
    }
    control.textContent = action.label;
    row.append(control);
  }
  box.append(row);
  overlay.append(box);
  document.body.append(overlay);
  row.firstChild.focus();
}

function removeDialog() {
  const shown = document.getElementById("qp-lock");
  if (shown) {
    shown.remove();
  }
}

/** A request that writes (any method but GET, HEAD and OPTIONS, after a method override). */
function writes(options) {
  const headers = options.headers || {};
  const override = Object.keys(headers).find((name) => name.toLowerCase() === "x-http-method-override");
  const method = String((override && headers[override]) || options.method || "GET").toUpperCase();
  return method !== "GET" && method !== "HEAD" && method !== "OPTIONS";
}

/**
 * The post lock of post `id` of type `type`.
 *
 * - `take()` takes the lock; when another user holds it, shows the "already being edited" dialog and resolves only
 *   once the user has taken over ("Exit editor" leaves for the type's list). Resolves when the lock is held.
 * - `takeover()` takes the lock whatever is stored.
 * - `refresh()` refreshes the lock: `"refreshed"`, `"skipped"` (the tab is hidden: nothing sent), `"lost"` (another
 *   user has taken over: one autosave, saving frozen, the "taken over" dialog) or `"stopped"` (nothing sent).
 * - `start()` refreshes every configured interval while the tab is visible, and at once when it becomes visible
 *   again; `stop()` ends that.
 * - `release()` stops and releases the lock with its one-time token, for a page being left.
 * - `resume()` starts again after the page came back from the browser's page cache (the lock was released).
 * - `guard` is the api-fetch middleware that, once the post is taken over (after its one autosave), refuses every
 *   write to the post's own routes (`ctx.postPath`, such as `wp/v2/posts/7`, and below it: its autosaves); other
 *   writes, such as the user's preferences, still go.
 * - `state` is `{activePostLock, releaseToken, lockedBy}`.
 *
 * @param {{config: {restRoot: string, adminUrl: string, lockRefreshInterval: number}, api: object,
 *     postPath: string}} ctx
 */
export function createLocks(ctx, type, id) {
  const { config, api, postPath } = ctx;
  const path = `quarkpress/v1/locks/${id}`;
  const exit = `${config.adminUrl}edit.php?post_type=${encodeURIComponent(type)}`;
  const state = { activePostLock: null, releaseToken: null, lockedBy: null };
  let timer = null;
  let stopped = true;
  let lost = null;
  let frozen = false;
  let running = null;

  function hold(lock, token) {
    state.activePostLock = lock;
    state.releaseToken = token;
    state.lockedBy = null;
  }

  async function takeover() {
    const answer = await api.fetchJson(`${path}/takeover`, { method: "POST" });
    hold(answer.activePostLock, answer.release_token);
    return answer;
  }

  async function take() {
    const answer = await api.fetchJson(path, { method: "POST" });
    if (!answer.isLocked) {
      hold(answer.activePostLock, answer.release_token);
      return answer;
    }
    const user = answer.user || {};
    state.lockedBy = user;
    return new Promise((resolve, reject) => {
      showDialog(
        LOCKED_TITLE,
        user.avatar,
        [named(user.name, LOCKED_TEXT, LOCKED_ANONYMOUS), paragraph(TAKE_OVER_WARNING)],
        [
          {
            label: "Take over",
            onClick: () =>
              takeover().then(
                (taken) => {
                  removeDialog();
                  resolve(taken);
                },
                (failure) => {
                  removeDialog();
                  reject(failure);
                },
              ),
          },
          { label: "Exit editor", href: exit },
        ],
      );
    });
  }

  /** Another user has taken over: one autosave, then saving is frozen and the dialog shown. */
  async function lose(error) {
    lost = error;
    stop();
    state.lockedBy = { name: error.name, avatar: error.avatar_src_2x || error.avatar_src };
    const editor = window.wp.data.dispatch("core/editor");
    try {
      await editor.autosave();
    } catch (failure) {
      console.warn("QuarkPress admin: the autosave after the takeover failed.", failure);
    }
    frozen = true;
    editor.lockPostSaving(FROZEN);
    editor.lockPostAutosaving(FROZEN);
    showDialog(
      TAKEN_OVER_TITLE,
      state.lockedBy.avatar,
      [named(error.name, TAKEN_OVER_TEXT, TAKEN_OVER_ANONYMOUS)],
      [{ label: "Exit editor", href: exit }],
    );
  }

  async function refresh() {
    if (stopped) {
      return "stopped";
    }
    if (document.visibilityState !== "visible") {
      return "skipped";
    }
    if (running) {
      return running;
    }
    running = (async () => {
      try {
        const answer = await api.fetchJson(path, { method: "PUT" });
        if (lost) {
          return "stopped";
        }
        if (answer.lock_error) {
          await lose(answer.lock_error);
          return "lost";
        }
        hold(answer.new_lock, answer.release_token);
        return "refreshed";
      } finally {
        running = null;
      }
    })();
    return running;
  }

  function tick() {
    refresh().catch((failure) => console.warn("QuarkPress admin: the post lock could not be refreshed.", failure));
  }

  function visible() {
    if (document.visibilityState === "visible") {
      tick();
    }
  }

  function start() {
    if (timer || lost) {
      return;
    }
    stopped = false;
    timer = setInterval(tick, intervalSeconds(config) * 1000);
    document.addEventListener("visibilitychange", visible);
  }

  function stop() {
    stopped = true;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    document.removeEventListener("visibilitychange", visible);
  }

  /**
   * Releases the lock when the page is left: a beacon with the lock value and its one-time token to the release
   * route (a beacon can carry no DELETE and no Authorization header, hence the method override and the token), or,
   * when the browser has no beacon or refuses it, a request that outlives the page. Answers whether one was sent.
   */
  function release() {
    stop();
    if (lost || !state.activePostLock || !state.releaseToken) {
      return false;
    }
    const url = `${config.restRoot}${path}?_method=DELETE`;
    const body = JSON.stringify({ active_post_lock: state.activePostLock, release_token: state.releaseToken });
    state.releaseToken = null;
    let sent = false;
    try {
      sent =
        typeof navigator.sendBeacon === "function" &&
        navigator.sendBeacon(url, new Blob([body], { type: "application/json" }));
    } catch (refused) {
      sent = false;
    }
    if (!sent) {
      try {
        fetch(url, {
          method: "POST",
          keepalive: true,
          credentials: "omit",
          headers: { "Content-Type": "application/json" },
          body,
        }).catch(() => {});
      } catch (refused) {
        return false;
      }
    }
    return true;
  }

  /** The page came back from the browser's page cache after a release: refresh at once (taking the lock again). */
  function resume() {
    if (lost || !state.activePostLock) {
      return;
    }
    start();
    tick();
  }

  /** Whether a request path is the post's own item route or one below it. */
  function ownRoute(path) {
    return path !== null && (path === postPath || path.startsWith(`${postPath}/`));
  }

  async function guard(options, next) {
    if (frozen && writes(options) && ownRoute(restRoute(config, options))) {
      throw { code: "quarkpress_post_lock_lost", message: lost.text || "", data: { status: 409 } };
    }
    return next(options);
  }

  return { take, takeover, refresh, release, start, stop, resume, guard, state };
}
