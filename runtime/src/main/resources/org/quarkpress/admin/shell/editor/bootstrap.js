// SPDX-License-Identifier: GPL-2.0-or-later
// Mounts WordPress's block editor for one post: the editor route's settings, preloaded answers, block definitions and
// date settings; the post lock, taken before anything is mounted (a lock another user holds shows WordPress's dialog
// first); the api-fetch middlewares (root URL, the user's token, uploads, preloads, and the freeze once the post is
// taken over); the date and locale settings; the block categories and server definitions; the user's stored
// preferences; the canvas styles WordPress hands the editor; the preview session, so that the front end renders the
// user's previews; then wp.editPost.initializeEditor on #editor, as WordPress's edit screen calls it; then the lock is
// refreshed while the editor is open and released when it is left.

import { installApiFetch } from "../api.js";
import { createLocks } from "./locks.js";
import { createPreviewSession } from "./preview.js";
import { applyLocale, presetVariables } from "./settings.js";

/** What the shell shows over the editor when the session cannot be renewed. */
export const SESSION_EXPIRED = "Your session has expired. You can log in again from this page or go to the login page.";

/** The editor's own file of base canvas styles, whose text WordPress hands the editor as its default styles. */
const DEFAULT_EDITOR_STYLES = "wp-includes/css/dist/block-editor/default-editor-styles.css";

/** Text for a quoted HTML attribute or element. */
function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

/** The body of a preloaded answer, by WordPress's preload path; undefined when the route left it out. */
function preloaded(preload, path) {
  const entry = preload && preload[path];
  return entry ? entry.body : undefined;
}

/**
 * Shows a dialog over the whole page that only the action closes (the page reloads or leaves): the text and one
 * button. Shown once.
 */
export function showOverlay(text, action) {
  if (document.getElementById("qp-overlay")) {
    return;
  }
  const overlay = document.createElement("div");
  overlay.id = "qp-overlay";
  overlay.className = "qp-overlay";
  const box = document.createElement("div");
  box.className = "qp-overlay-box";
  box.setAttribute("role", "alertdialog");
  box.setAttribute("aria-modal", "true");
  box.setAttribute("aria-describedby", "qp-overlay-text");
  const paragraph = document.createElement("p");
  paragraph.id = "qp-overlay-text";
  paragraph.textContent = text;
  const button = document.createElement("button");
  button.type = "button";
  button.className = "button button-primary";
  button.textContent = action.label;
  button.addEventListener("click", () => {
    button.disabled = true;
    Promise.resolve(action.onClick()).catch((failure) => {
      button.disabled = false;
      console.warn("QuarkPress admin: the action failed.", failure);
    });
  });
  box.append(paragraph, button);
  overlay.append(box);
  document.body.append(overlay);
  button.focus();
}

/** The text of the editor's base canvas styles, fetched once; no default styles when it cannot be read. */
async function defaultEditorStyles(config) {
  try {
    const response = await fetch(`${config.siteRoot}/${DEFAULT_EDITOR_STYLES}?ver=${encodeURIComponent(config.wordpress)}`, {
      credentials: "same-origin",
    });
    if (!response.ok) {
      throw new Error(`answered ${response.status}`);
    }
    return [{ css: await response.text() }];
  } catch (failure) {
    console.warn("QuarkPress admin: the editor's default styles could not be read.", failure);
    return [];
  }
}

/**
 * The HTML WordPress puts in the canvas iframe's head: the iframe's style sheets the page names on #editor (with the
 * site's absolute addresses), the preset variables, and the scripts the iframe loads (the polyfill), also named on
 * #editor.
 */
function resolvedAssets(config, settings) {
  const mount = document.getElementById("editor");
  const sheets = JSON.parse(mount.dataset.iframeStyles || "[]");
  const links = sheets.map((sheet) => {
    const href = `${config.siteRoot}/${sheet.file}?ver=${sheet.ver}`;
    return `<link rel='stylesheet' id='${escapeHtml(sheet.handle)}-css' href='${escapeHtml(href)}' media='all' />`;
  });
  links.push(
    `<style id="global-styles-css-custom-properties-inline-css">\n${presetVariables(
      settings.__experimentalFeatures,
    )}\n</style>`,
  );
  const scripts = JSON.parse(mount.dataset.iframeScripts || "[]").map((script) => {
    const src = `${config.siteRoot}/${script.file}?ver=${script.ver}`;
    return `<script src="${escapeHtml(src)}" id="${escapeHtml(script.handle)}-js"></script>\n`;
  });
  return { styles: `${links.join("\n")}\n`, scripts: scripts.join("") };
}

/**
 * The user's stored editor preferences, as WordPress prints them into its page: the object, or `""` when there are
 * none (REST answers an empty array for no preferences).
 */
export function storedPreferences(user) {
  const stored = user && user.meta && user.meta.persisted_preferences;
  return stored && typeof stored === "object" && !Array.isArray(stored) ? stored : "";
}

/**
 * Restores the user's editor preferences as WordPress's page does: from the user's own record, read once in the edit
 * context (the preloaded record carries no preferences), so that newer preferences saved elsewhere win over this
 * browser's copy. Without that record the browser's copy is used.
 */
async function restorePreferences(api, me) {
  const wp = window.wp;
  let user = me;
  try {
    user = await api.fetchJson("wp/v2/users/me?context=edit");
  } catch (failure) {
    console.warn("QuarkPress admin: the stored editor preferences could not be read.", failure);
  }
  wp.data.use(wp.data.plugins.persistence, { storageKey: `WP_DATA_USER_${me.id}` });
  const layer = wp.preferencesPersistence.__unstableCreatePersistenceLayer(
    user === me ? "" : storedPreferences(user),
    String(me.id),
  );
  wp.data.dispatch(wp.preferences.store).setPersistenceLayer(layer);
}

/**
 * Loads the editor route for the post, takes the post lock and mounts the editor. Resolves to
 * `{outcome: "mounted", post}` once `initializeEditor` has run, or `{outcome: "trash"}` without mounting for a post in
 * the trash; rejects with the route's or the lock's `RestError` (404, 403, 401). While another user holds the lock,
 * WordPress's dialog is shown and nothing is mounted until the user takes over. The lock is
 * `window.quarkpressAdmin.locks`, the preview session `window.quarkpressAdmin.preview` (opened before the editor
 * mounts; the editor is mounted even when it cannot be opened, only previews then show nothing).
 *
 * @param {{config: object, auth: object, api: object, type: string, id: number}} ctx
 */
export async function bootstrap(ctx) {
  const { config, auth, api, type, id } = ctx;
  const wp = window.wp;
  const route = await api.fetchJson(`quarkpress/v1/editor/${encodeURIComponent(type)}/${id}`);
  const preload = route.preload || {};
  const typeObject = preloaded(preload, `/wp/v2/types/${type}?context=edit`);
  const namespace = (typeObject && typeObject.rest_namespace) || "wp/v2";
  const base = (typeObject && typeObject.rest_base) || type;
  const post = preloaded(preload, `/${namespace}/${base}/${id}?context=edit`);
  if (post && post.status === "trash") {
    return { outcome: "trash" };
  }

  const locks = createLocks({ config, api, postPath: `${namespace}/${base}/${id}` }, type, id);
  window.quarkpressAdmin.locks = locks;
  await locks.take();
  window.addEventListener("pagehide", () => locks.release());
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
      locks.resume();
    }
  });

  installApiFetch(config, auth, preload);
  wp.apiFetch.use(locks.guard);

  function sessionExpired() {
    const editor = wp.data.dispatch("core/editor");
    editor.lockPostSaving("quarkpress-session");
    editor.lockPostAutosaving("quarkpress-session");
    showOverlay(SESSION_EXPIRED, { label: "Log in again", onClick: () => auth.signIn(location.href) });
  }
  auth.onExpired(sessionExpired);

  // opened beside the rest of the preparation, and awaited before the editor mounts
  const preview = createPreviewSession({ api, onUnauthorized: sessionExpired });
  window.quarkpressAdmin.preview = preview;
  const previewOpened = preview.open().catch(() => {
    // reported by the session; editing goes on without previews
  });
  applyLocale(route.dateSettings);
  wp.blocks.setCategories(route.settings.blockCategories || []);
  wp.blocks.unstable__bootstrapServerSideBlockDefinitions(route.blockDefinitions || {});
  const me = preloaded(preload, "/wp/v2/users/me");
  if (me) {
    await restorePreferences(api, me);
  }

  const settings = {
    ...route.settings,
    // the lock is the shell's: the editor's own dialog, which the route's report of another user's lock would open,
    // never shows
    postLock: { isLocked: false },
    defaultEditorStyles: await defaultEditorStyles(config),
  };
  settings.__unstableResolvedAssets = resolvedAssets(config, settings);
  const newPost = !!post && post.status === "auto-draft";
  const initialEdits = newPost ? { title: "", content: "", excerpt: "" } : [];

  const labels = (typeObject && typeObject.labels) || {};
  const heading = (newPost ? labels.add_new_item : labels.edit_item) || labels.edit_item || "";
  document.getElementById("qp-editor-title").textContent = heading;
  const title = post && post.title && !newPost ? post.title.raw : "";
  document.title = `${title || heading} ‹ QuarkPress`;

  await previewOpened;

  window._wpLoadBlockEditor = new Promise((resolve) => {
    wp.domReady(() => {
      resolve(wp.editPost.initializeEditor("editor", type, id, settings, initialEdits));
    });
  });
  window._wpLoadBlockEditor.then(() => {
    wp.data.dispatch("core/edit-post").setAvailableMetaBoxesPerLocation({ side: [], normal: [], advanced: [] });
  });
  await window._wpLoadBlockEditor;
  locks.start();
  return { outcome: "mounted", post };
}
