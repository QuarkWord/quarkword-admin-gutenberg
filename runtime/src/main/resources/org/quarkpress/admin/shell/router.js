// SPDX-License-Identifier: GPL-2.0-or-later
// Which screen an admin address names, by its file name and query, as wp-admin's own files do.

/**
 * The screen for an address (a `Location` or `URL`) below the admin path, and the query parameters it uses.
 *
 * @returns {{screen: string, params: Object<string, string>}}
 */
export function route(where) {
  const url = new URL(where.href);
  const file = url.pathname.slice(url.pathname.lastIndexOf("/") + 1);
  const query = url.searchParams;
  const pick = (...names) => {
    const params = {};
    for (const name of names) {
      if (query.has(name)) {
        params[name] = query.get(name);
      }
    }
    return params;
  };
  switch (file) {
    case "":
    case "index.php":
      return { screen: "posts", params: {} };
    case "edit.php": {
      const params = pick("post_status", "paged", "trashed", "untrashed", "deleted", "ids");
      if (query.get("post_type") === "page") {
        return { screen: "pages", params };
      }
      return { screen: "posts", params };
    }
    case "upload.php":
      return { screen: "media", params: pick("paged") };
    case "post-new.php":
      return { screen: "new", params: { post_type: query.get("post_type") || "post" } };
    case "post.php":
      if (query.get("action") === "edit") {
        return { screen: "edit", params: pick("post", "get-post-lock") };
      }
      return { screen: "notFound", params: {} };
    case "revision.php":
      return { screen: "revision", params: pick("revision") };
    default:
      return { screen: "notFound", params: {} };
  }
}
