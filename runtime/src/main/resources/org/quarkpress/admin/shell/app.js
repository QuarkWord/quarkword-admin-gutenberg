// SPDX-License-Identifier: GPL-2.0-or-later
// The shell page's entry: settings, sign-in, then the screen the address names. Screens change by real navigations
// between the admin folder's files (edit.php, upload.php); the address bar is only ever rewritten to a file of the
// same folder, so the page's relative URLs keep resolving the same way.

import { loadConfig } from "./config.js";
import { createAuth } from "./auth.js";
import { createApi } from "./api.js";
import { rememberType, route } from "./router.js";
import { renderSignIn } from "./screens/signin.js";
import { h, renderList } from "./screens/lists.js";
import { clearNotices, notice } from "./screens/notices.js";

const main = document.getElementById("qp-main");
const header = document.querySelector("#quarkpress-admin > header");

/** The screens the server answers with the editor page, never with this one. */
const EDITOR_SCREENS = new Set(["edit", "revision"]);

/** Creates a draft of `type` and opens it in the editor. */
async function createDraft(api, config, type) {
  try {
    const draft = await api.fetchJson("quarkpress/v1/drafts", { method: "POST", body: { type } });
    rememberType(draft.id, draft.type);
    location.replace(`${config.adminUrl}post.php?post=${draft.id}&action=edit`);
  } catch (failure) {
    notice(failure.message, { error: true });
  }
}

function renderHeader(auth) {
  const user = auth.user();
  const name = user && user.profile ? user.profile.name || user.profile.preferred_username : "";
  header.replaceChildren(
    h("a", { class: "screen-reader-text qp-skip", href: "#qp-main" }, "Skip to main content"),
    h(
      "nav",
      { "aria-label": "Main menu" },
      h(
        "ul",
        {},
        h("li", {}, h("a", { href: "edit.php" }, "All Posts")),
        h("li", {}, h("a", { href: "edit.php?post_type=page" }, "All Pages")),
        h("li", {}, h("a", { href: "upload.php" }, "Media Library")),
      ),
    ),
    h(
      "div",
      { class: "qp-account" },
      name ? h("span", {}, name) : null,
      " ",
      h("button", { type: "button", class: "button-link", onclick: () => auth.signOut() }, "Log Out"),
    ),
  );
}

function showSignIn(auth) {
  header.replaceChildren();
  clearNotices();
  renderSignIn(main, auth).focus();
}

async function start() {
  let config;
  try {
    config = await loadConfig();
  } catch (failure) {
    main.replaceChildren(h("div", { class: "qp-error" }, h("p", {}, failure.message)));
    throw failure;
  }
  const auth = createAuth(config);
  const api = createApi(config, auth);
  Object.assign(window.quarkpressAdmin, { config, auth, api, version: config.wordpress });

  await auth.completeSignIn();
  const where = route(location);
  if (EDITOR_SCREENS.has(where.screen)) {
    // back from the sign-in server to an editor address: load the editor page there
    location.reload();
    return;
  }
  if (!auth.user()) {
    showSignIn(auth);
    return;
  }
  if (where.screen === "new") {
    await createDraft(api, config, where.params.post_type);
    return;
  }
  renderHeader(auth);
  const ctx = {
    api,
    main,
    signedOut: () => showSignIn(auth),
    createDraft: (type) => createDraft(api, config, type),
  };
  if (where.screen === "notFound") {
    const title = h("h1", { tabindex: "-1" }, "Not Found");
    main.replaceChildren(title, h("div", { class: "qp-error" }, h("p", {}, "This admin page does not exist.")));
    title.focus();
    return;
  }
  await renderList(where.screen, where.params, ctx);
}

window.quarkpressAdmin = { ready: null };
window.quarkpressAdmin.ready = start();
