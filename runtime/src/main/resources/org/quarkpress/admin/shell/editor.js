// SPDX-License-Identifier: GPL-2.0-or-later
// The editor page's entry: settings, sign-in, then the post the address names. A new post (post-new.php) is created
// first; a post that does not exist, that the user may not edit or that is in the trash gets WordPress's message
// instead of the editor; revision.php points back to the editor; post.php?get-post-lock=1 (WordPress's "Take over"
// address, which WordPress honours only with the nonce of a link it printed) opens the post as any opening does, so
// another user's lock shows the dialog whose button alone takes over. The address bar is only ever rewritten to a file
// of the same folder, so the page's relative URLs keep resolving the same way.

import { loadConfig } from "./config.js";
import { createAuth } from "./auth.js";
import { createApi } from "./api.js";
import { forgetType, rememberType, rememberedType, route } from "./router.js";
import { renderSignIn } from "./screens/signin.js";
import { h } from "./screens/lists.js";
import { bootstrap } from "./editor/bootstrap.js";

export const NOT_FOUND = "You attempted to edit an item that does not exist. Perhaps it was deleted?";
export const NOT_ALLOWED = "Sorry, you are not allowed to edit this item.";
export const IN_TRASH = "You cannot edit this item because it is in the Trash. Please restore it and try again.";
export const REVISIONS = "Revisions are shown inside the editor: open the post and choose the revision.";

const main = document.getElementById("qp-main");

/** Types whose items the editor never opens. */
const NOT_EDITED = new Set(["attachment"]);

/** Ends the screen with a message (and links) instead of the editor; `outcome` names it for scripts and tests. */
function message(outcome, text, ...links) {
  const title = h("h1", { tabindex: "-1" }, document.title);
  main.replaceChildren(
    title,
    h("div", { class: "qp-error" }, h("p", {}, text), links.length ? h("p", {}, links) : null),
  );
  title.focus();
  return outcome;
}

/**
 * The type of post `id`: remembered, or found by asking each type's item route in turn (posts, pages, then the other
 * types), with only the fields that say it exists. A 403 also names the type: the post exists and the user may not
 * edit it. Resolves to `{type, status?, forbidden?, remembered?}`, or null when no type has the post.
 */
async function resolveType(api, id) {
  const remembered = rememberedType(id);
  if (remembered) {
    return { type: remembered, remembered: true };
  }
  const types = await api.fetchJson("wp/v2/types?context=edit");
  const slugs = ["post", "page", ...Object.keys(types).filter((slug) => slug !== "post" && slug !== "page")];
  for (const slug of slugs) {
    const type = types[slug];
    if (!type || !type.rest_base || NOT_EDITED.has(slug)) {
      continue;
    }
    const namespace = type.rest_namespace || "wp/v2";
    try {
      const post = await api.fetchJson(`${namespace}/${type.rest_base}/${id}?context=edit&_fields=id,type,status`);
      rememberType(id, slug);
      return { type: slug, status: post.status };
    } catch (failure) {
      if (failure.status === 403) {
        return { type: slug, forbidden: true };
      }
      if (failure.status !== 404) {
        throw failure;
      }
    }
  }
  return null;
}

/** The outcome of a failed request: sign-in for 401, WordPress's refusals for 403 and 404, else its message. */
function failed(failure, auth) {
  if (failure.status === 401) {
    renderSignIn(main, auth).focus();
    return "signIn";
  }
  if (failure.status === 403) {
    return message("forbidden", NOT_ALLOWED);
  }
  if (failure.status === 404) {
    return message("notFound", NOT_FOUND);
  }
  return message("error", failure.message);
}

/** Opens post `id` in the editor, or shows why it cannot be. */
async function edit(env, id, known) {
  let resolved = known;
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!resolved) {
      try {
        resolved = await resolveType(env.api, id);
      } catch (failure) {
        return failed(failure, env.auth);
      }
    }
    if (!resolved) {
      return message("notFound", NOT_FOUND);
    }
    if (resolved.forbidden) {
      return message("forbidden", NOT_ALLOWED);
    }
    if (resolved.status === "trash") {
      return message("trash", IN_TRASH);
    }
    let result;
    try {
      result = await bootstrap({ ...env, type: resolved.type, id });
    } catch (failure) {
      if (failure.status === 404 && resolved.remembered && attempt === 0) {
        // the remembered type was wrong: look the post up once
        forgetType(id);
        resolved = null;
        continue;
      }
      return failed(failure, env.auth);
    }
    if (result.outcome === "trash") {
      return message("trash", IN_TRASH);
    }
    window.quarkpressAdmin.editor = { type: resolved.type, id };
    return "mounted";
  }
  return message("notFound", NOT_FOUND);
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
  if (!auth.user()) {
    renderSignIn(main, auth).focus();
    return "signIn";
  }
  const where = route(location);
  const env = { config, auth, api };
  if (where.screen === "revision") {
    return message("revision", REVISIONS, h("a", { href: "edit.php" }, "All Posts"));
  }
  if (where.screen === "new") {
    let draft;
    try {
      draft = await api.fetchJson("quarkpress/v1/drafts", { method: "POST", body: { type: where.params.post_type } });
    } catch (failure) {
      return failed(failure, auth);
    }
    rememberType(draft.id, draft.type);
    history.replaceState(null, "", `post.php?post=${draft.id}&action=edit`);
    return edit(env, draft.id, { type: draft.type, status: draft.status });
  }
  if (where.screen === "edit") {
    const id = Number.parseInt(where.params.post || "", 10);
    if (!(id > 0) || String(id) !== where.params.post) {
      return message("notFound", NOT_FOUND);
    }
    if (where.params["get-post-lock"] !== undefined) {
      // no takeover by address: the plain edit address, and the lock taken as any opening takes it
      history.replaceState(null, "", `post.php?post=${id}&action=edit`);
    }
    return edit(env, id, null);
  }
  return message("notFound", NOT_FOUND);
}

window.quarkpressAdmin = { ready: null, outcome: null };
window.quarkpressAdmin.ready = start().then(
  (outcome) => {
    window.quarkpressAdmin.outcome = outcome;
    return outcome;
  },
  (failure) => {
    window.quarkpressAdmin.outcome = "error";
    throw failure;
  },
);
