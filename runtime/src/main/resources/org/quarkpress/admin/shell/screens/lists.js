// SPDX-License-Identifier: GPL-2.0-or-later
// The posts, pages and media lists: wp-admin's list screens on the REST API (edit context), with the status views and
// their counts, paging, and the row actions Move to Trash, Restore and Delete Permanently.

import { clearNotices, deletedText, notice, trashedNotice, untrashedText } from "./notices.js";

/** An element with attributes (`on<event>` adds a listener) and children (text or nodes). */
export function h(tag, attributes, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes || {})) {
    if (value === null || value === undefined || value === false) {
      continue;
    }
    if (name.startsWith("on")) {
      element.addEventListener(name.slice(2), value);
    } else {
      element.setAttribute(name, value === true ? "" : String(value));
    }
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined && child !== false) {
      element.append(child instanceof Node ? child : String(child));
    }
  }
  return element;
}

export const FORBIDDEN = "Sorry, you are not allowed to access this page.";

const POST_LISTS = {
  posts: {
    type: "post",
    base: "posts",
    heading: "Posts",
    addNew: "Add New Post",
    addNewHref: "post-new.php",
    query: "",
  },
  pages: {
    type: "page",
    base: "pages",
    heading: "Pages",
    addNew: "Add New Page",
    addNewHref: "post-new.php?post_type=page",
    query: "post_type=page",
  },
};

/** wp-admin's status views; All is every status but the trash (and auto-drafts). */
const VIEWS = [
  { key: "all", label: "All", statuses: "publish,future,draft,pending,private" },
  { key: "publish", label: "Published", statuses: "publish" },
  { key: "future", label: "Scheduled", statuses: "future" },
  { key: "draft", label: "Drafts", statuses: "draft" },
  { key: "pending", label: "Pending", statuses: "pending" },
  { key: "private", label: "Private", statuses: "private" },
  { key: "trash", label: "Trash", statuses: "trash" },
];

/** The states wp-admin prints after a title. */
const POST_STATES = { draft: "Draft", pending: "Pending", future: "Scheduled", private: "Private" };

const PER_PAGE = 20;

/** The parameters that only carry a notice: read once, then left out of the address and of later renders. */
const NOTICE_PARAMS = ["trashed", "untrashed", "deleted", "ids"];

/**
 * Shows a list in `ctx.main`: `kind` is `posts`, `pages` or `media`; `params` the screen's query (`post_status`,
 * `paged`, and `trashed` with `ids` for the notice after a trash); `message` a notice to show once it is rendered.
 *
 * @param {{api: object, main: HTMLElement, signedOut: Function, createDraft: Function}} ctx
 */
export async function renderList(kind, params, ctx, message) {
  ctx.main.setAttribute("aria-busy", "true");
  try {
    if (kind === "media") {
      await renderMedia(params, ctx, message);
    } else {
      await renderPosts(POST_LISTS[kind], kind, params, ctx, message);
    }
  } finally {
    ctx.main.setAttribute("aria-busy", "false");
  }
}

function query(entries) {
  const search = new URLSearchParams();
  for (const [name, value] of entries) {
    search.set(name, String(value));
  }
  return search.toString();
}

function href(list, extra) {
  const parts = [list.query, extra].filter((part) => part);
  return parts.length ? `edit.php?${parts.join("&")}` : "edit.php";
}

function pageNumber(params) {
  const page = Number.parseInt(params.paged || "1", 10);
  return Number.isFinite(page) && page > 0 ? page : 1;
}

/** Drops the notice parameters from the address bar (the same file, so relative URLs resolve as before). */
function forgetNoticeParams() {
  const url = new URL(location.href);
  let changed = false;
  for (const name of NOTICE_PARAMS) {
    if (url.searchParams.has(name)) {
      url.searchParams.delete(name);
      changed = true;
    }
  }
  if (changed) {
    history.replaceState(history.state, "", url.pathname + url.search + url.hash);
  }
}

function withoutNoticeParams(params) {
  const kept = { ...params };
  for (const name of NOTICE_PARAMS) {
    delete kept[name];
  }
  return kept;
}

/**
 * Whether the user may not see the list: a 403, or the status filter refused for the user (WordPress answers that
 * with 400 `rest_invalid_param` and the status argument's own `rest_forbidden_status` error in its details).
 */
function forbidden(failure) {
  if (failure.status === 403) {
    return true;
  }
  const details = failure.data && failure.data.details;
  return (
    failure.code === "rest_invalid_param" &&
    !!details &&
    !!details.status &&
    details.status.code === "rest_forbidden_status"
  );
}

/** The screen after a failed list request: the sign-in screen for 401, wp-admin's refusal when not allowed. */
function failed(failure, heading, ctx) {
  if (failure.status === 401) {
    ctx.signedOut();
    return;
  }
  const text = forbidden(failure) ? FORBIDDEN : failure.message;
  const title = h("h1", { tabindex: "-1" }, heading);
  ctx.main.replaceChildren(title, h("div", { class: "qp-error" }, h("p", {}, text)));
  title.focus();
}

async function total(ctx, list, statuses) {
  try {
    const answer = await ctx.api.fetchResponse(
      `wp/v2/${list.base}?${query([
        ["context", "edit"],
        ["status", statuses],
        ["per_page", 1],
        ["_fields", "id"],
      ])}`,
    );
    return Number.parseInt(answer.headers.get("X-WP-Total") || "0", 10);
  } catch (failure) {
    return null;
  }
}

function localDate(text) {
  // "2026-11-16T10:05:00", the site's local time: "2026/11/16 at 10:05 am"
  if (!text) {
    return "";
  }
  const [day, time] = text.split("T");
  const [year, month, date] = day.split("-");
  const [hours, minutes] = time.split(":");
  const hour = Number.parseInt(hours, 10);
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${year}/${month}/${date} at ${twelve}:${minutes} ${hour < 12 ? "am" : "pm"}`;
}

function titleOf(item) {
  const raw = item.title && (item.title.raw !== undefined ? item.title.raw : item.title.rendered);
  return raw ? raw : "(no title)";
}

async function renderPosts(list, kind, params, ctx, message) {
  const view = VIEWS.find((candidate) => candidate.key === params.post_status) || VIEWS[0];
  const page = pageNumber(params);
  let answer;
  try {
    answer = await ctx.api.fetchResponse(
      `wp/v2/${list.base}?${query([
        ["context", "edit"],
        ["status", view.statuses],
        ["per_page", PER_PAGE],
        ["page", page],
        ["_fields", "id,title,status,date,modified,type,link"],
      ])}`,
    );
  } catch (failure) {
    failed(failure, list.heading, ctx);
    return;
  }
  const counts = await Promise.all(VIEWS.map((candidate) => total(ctx, list, candidate.statuses)));
  const totalPages = Number.parseInt(answer.headers.get("X-WP-TotalPages") || "0", 10);
  const kept = withoutNoticeParams(params);
  const rerender = (text) => renderList(kind, kept, ctx, text);

  const act = async (work, done) => {
    try {
      await work();
    } catch (failure) {
      notice(failure.message, { error: true });
      return;
    }
    await done();
  };
  const rows = answer.data.map((item) => {
    const trashed = item.status === "trash";
    const title = titleOf(item);
    const actions = trashed
      ? [
          h(
            "button",
            {
              type: "button",
              class: "button-link",
              onclick: () =>
                act(
                  () =>
                    ctx.api.fetchJson(`wp/v2/${list.base}/${item.id}`, { method: "PUT", body: { status: "draft" } }),
                  () => rerender(untrashedText(1, list.type)),
                ),
            },
            "Restore",
          ),
          h(
            "button",
            {
              type: "button",
              class: "button-link qp-delete",
              onclick: () =>
                act(
                  () => ctx.api.fetchJson(`wp/v2/${list.base}/${item.id}?force=true`, { method: "DELETE" }),
                  () => rerender(deletedText(1, list.type)),
                ),
            },
            "Delete Permanently",
          ),
        ]
      : [
          h(
            "button",
            {
              type: "button",
              class: "button-link qp-delete",
              onclick: () =>
                act(
                  () => ctx.api.fetchJson(`wp/v2/${list.base}/${item.id}`, { method: "DELETE" }),
                  () => renderList(kind, { ...kept, trashed: "1", ids: String(item.id) }, ctx),
                ),
            },
            "Move to Trash",
          ),
        ];
    const state = POST_STATES[item.status];
    const dateLabel =
      item.status === "publish" || item.status === "private"
        ? "Published"
        : item.status === "future"
          ? "Scheduled"
          : "Last Modified";
    const shown = dateLabel === "Last Modified" ? item.modified : item.date;
    return h(
      "tr",
      {},
      h(
        "td",
        { class: "column-title" },
        h(
          "strong",
          {},
          trashed ? title : h("a", { class: "row-title", href: `post.php?post=${item.id}&action=edit` }, title),
          state && view.key !== item.status ? h("span", { class: "post-state" }, ` — ${state}`) : null,
        ),
        h("div", { class: "row-actions" }, actions),
      ),
      h("td", { class: "column-date" }, dateLabel, h("br", {}), localDate(shown)),
    );
  });

  const views = h(
    "ul",
    { class: "subsubsub" },
    VIEWS.map((candidate, index) => {
      const count = counts[index];
      const current = candidate.key === view.key;
      if (!current && candidate.key !== "all" && !count) {
        return null;
      }
      const target = candidate.key === "all" ? href(list, "") : href(list, `post_status=${candidate.key}`);
      return h(
        "li",
        {},
        h(
          "a",
          { href: target, "aria-current": current ? "page" : null, class: current ? "current" : null },
          candidate.label,
          " ",
          h("span", { class: "count" }, `(${count === null ? 0 : count})`),
        ),
      );
    }),
  );

  const heading = h("h1", { tabindex: "-1", class: "wp-heading-inline" }, list.heading);
  const addNew = h(
    "a",
    {
      href: list.addNewHref,
      class: "page-title-action",
      onclick: (event) => {
        event.preventDefault();
        ctx.createDraft(list.type);
      },
    },
    list.addNew,
  );
  const table = h(
    "table",
    { class: "wp-list-table widefat" },
    h("caption", { class: "screen-reader-text" }, `${list.heading} list`),
    h("thead", {}, h("tr", {}, h("th", { scope: "col" }, "Title"), h("th", { scope: "col" }, "Date"))),
    h(
      "tbody",
      {},
      rows.length
        ? rows
        : h("tr", { class: "no-items" }, h("td", { colspan: "2" }, `No ${list.heading.toLowerCase()} found.`)),
    ),
  );
  ctx.main.replaceChildren(
    h("div", { class: "qp-title" }, heading, " ", addNew),
    h("nav", { "aria-label": `Filter ${list.heading.toLowerCase()} list` }, views),
    table,
    pagination(totalPages, page, (paged) => href(list, query(viewAndPage(view, paged)))),
  );

  clearNotices();
  const trashed = Number.parseInt(params.trashed || "0", 10);
  if (trashed > 0) {
    const ids = (params.ids || "")
      .split(",")
      .map((id) => Number.parseInt(id, 10))
      .filter((id) => id > 0);
    trashedNotice(trashed, ids, list.type, { api: ctx.api, rerender });
  }
  if (message) {
    notice(message);
  }
  forgetNoticeParams();
  heading.focus();
}

function viewAndPage(view, paged) {
  const entries = [];
  if (view.key !== "all") {
    entries.push(["post_status", view.key]);
  }
  if (paged > 1) {
    entries.push(["paged", paged]);
  }
  return entries;
}

/** "Previous" and "Next" links when there is more than one page. */
function pagination(totalPages, page, target) {
  if (totalPages <= 1) {
    return null;
  }
  return h(
    "nav",
    { class: "tablenav-pages", "aria-label": "Pagination" },
    page > 1 ? h("a", { href: target(page - 1), class: "prev-page" }, "Previous") : null,
    " ",
    h("span", { class: "paging-input" }, `${page} of ${totalPages}`),
    " ",
    page < totalPages ? h("a", { href: target(page + 1), class: "next-page" }, "Next") : null,
  );
}

async function renderMedia(params, ctx, message) {
  const page = pageNumber(params);
  const title = h("h1", { tabindex: "-1" }, "Media Library");
  let answer;
  try {
    answer = await ctx.api.fetchResponse(
      `wp/v2/media?${query([
        ["context", "edit"],
        ["per_page", PER_PAGE],
        ["page", page],
        ["_fields", "id,title,date,media_type,mime_type,source_url"],
      ])}`,
    );
  } catch (failure) {
    if (failure.code === "rest_no_route") {
      const text = h("p", {}, "The media module is not installed.");
      ctx.main.replaceChildren(title, h("div", { class: "qp-error" }, text));
      clearNotices();
      title.focus();
      return;
    }
    failed(failure, "Media Library", ctx);
    return;
  }
  const rows = answer.data.map((item) =>
    h(
      "tr",
      {},
      h(
        "td",
        { class: "column-title" },
        item.media_type === "image" ? h("img", { src: item.source_url, alt: "", width: "60" }) : null,
        " ",
        h("strong", {}, titleOf(item)),
      ),
      h("td", {}, item.mime_type),
      h("td", { class: "column-date" }, localDate(item.date)),
    ),
  );
  const totalPages = Number.parseInt(answer.headers.get("X-WP-TotalPages") || "0", 10);
  ctx.main.replaceChildren(
    h("div", { class: "qp-title" }, title),
    h(
      "table",
      { class: "wp-list-table widefat" },
      h("caption", { class: "screen-reader-text" }, "Media list"),
      h(
        "thead",
        {},
        h(
          "tr",
          {},
          h("th", { scope: "col" }, "File"),
          h("th", { scope: "col" }, "Type"),
          h("th", { scope: "col" }, "Date"),
        ),
      ),
      h(
        "tbody",
        {},
        rows.length ? rows : h("tr", { class: "no-items" }, h("td", { colspan: "3" }, "No media files found.")),
      ),
    ),
    pagination(totalPages, page, (paged) => (paged > 1 ? `upload.php?paged=${paged}` : "upload.php")),
  );
  clearNotices();
  if (message) {
    notice(message);
  }
  title.focus();
}
