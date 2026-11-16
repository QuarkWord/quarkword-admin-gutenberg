// SPDX-License-Identifier: GPL-2.0-or-later
// Notices above the screen, in the live region #qp-notices, with wp-admin's texts for what a list action did.

function container() {
  return document.getElementById("qp-notices");
}

/** Removes every notice. */
export function clearNotices() {
  container().replaceChildren();
}

/**
 * Shows a notice; `options.action` adds a button ({label, onClick}) after the text, `options.error` marks it as an
 * error. Returns the notice's element.
 */
export function notice(text, options = {}) {
  const box = document.createElement("div");
  box.className = options.error ? "qp-notice qp-notice-error" : "qp-notice";
  const paragraph = document.createElement("p");
  paragraph.append(document.createTextNode(text));
  if (options.action) {
    const action = document.createElement("button");
    action.type = "button";
    action.className = "button-link";
    action.textContent = options.action.label;
    action.addEventListener("click", () => {
      action.disabled = true;
      Promise.resolve(options.action.onClick()).finally(() => {
        action.disabled = false;
      });
    });
    paragraph.append(" ", action);
  }
  const dismiss = document.createElement("button");
  dismiss.type = "button";
  dismiss.className = "qp-notice-dismiss";
  dismiss.setAttribute("aria-label", "Dismiss this notice.");
  dismiss.textContent = "×";
  dismiss.addEventListener("click", () => box.remove());
  box.append(paragraph, dismiss);
  container().append(box);
  return box;
}

/** "1 post", "2 pages": the count and the type's noun. */
function counted(count, type) {
  const page = type === "page";
  const noun = count === 1 ? (page ? "page" : "post") : page ? "pages" : "posts";
  return `${count} ${noun}`;
}

/** The text after posts were restored from the trash. */
export function untrashedText(count, type) {
  return `${counted(count, type)} restored from the Trash.`;
}

/** The text after posts were deleted for good. */
export function deletedText(count, type) {
  return `${counted(count, type)} permanently deleted.`;
}

/**
 * The notice after posts were moved to the trash, with "Undo": of the posts the address names, those that are in the
 * trash go back to drafts (as WordPress's untrash does, which refuses a post not in the trash), then
 * `ctx.rerender(text)` shows the list again with the restored notice. The address is not trusted: a post it names that
 * is not in the trash is left as it is.
 *
 * @param {number} count
 * @param {number[]} ids
 * @param {string} type `post` or `page`
 * @param {{api: {fetchJson: Function}, rerender: Function}} ctx
 */
export function trashedNotice(count, ids, type, ctx) {
  const base = type === "page" ? "pages" : "posts";
  return notice(`${counted(count, type)} moved to the Trash.`, {
    action: {
      label: "Undo",
      onClick: async () => {
        let trashed = [];
        try {
          if (ids.length) {
            const search = new URLSearchParams({
              status: "trash",
              include: ids.join(","),
              context: "edit",
              _fields: "id",
              per_page: "100",
            });
            trashed = (await ctx.api.fetchJson(`wp/v2/${base}?${search}`)).map((post) => post.id);
          }
          for (const id of trashed) {
            await ctx.api.fetchJson(`wp/v2/${base}/${id}`, { method: "PUT", body: { status: "draft" } });
          }
        } catch (failure) {
          notice(failure.message, { error: true });
          return;
        }
        if (trashed.length) {
          await ctx.rerender(untrashedText(trashed.length, type));
        }
      },
    },
  });
}
