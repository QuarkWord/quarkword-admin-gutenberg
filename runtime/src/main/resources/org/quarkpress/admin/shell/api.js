// SPDX-License-Identifier: GPL-2.0-or-later
// The REST API as the shell calls it, and the api-fetch middlewares the editor uses: every request to the site's REST
// API carries the user's access token, and no other request does; a 401 (an expired token) is retried once after a
// silent renewal, and never more; without a token nothing is sent.

/** A REST error answer: its HTTP status and WordPress's error `code` and `message`. */
export class RestError extends Error {
  constructor(status, body) {
    super((body && body.message) || `The request failed (${status}).`);
    this.status = status;
    this.code = body && body.code;
    this.data = body && body.data;
  }
}

/** The error of a request that was not sent because no access token could be had (signed out, renewal failed). */
function notLoggedIn() {
  return new RestError(401, {
    code: "rest_not_logged_in",
    message: "You are not currently logged in.",
    data: { status: 401 },
  });
}

/**
 * The shell's own REST calls, below the site's REST root.
 *
 * @param {{restRoot: string}} config
 * @param {{getToken(): Promise<string|null>, refresh(): Promise<unknown>}} auth
 */
export function createApi(config, auth) {
  async function send(url, init, token) {
    const headers = new Headers(init.headers || {});
    headers.set("Accept", "application/json");
    let body = init.body;
    if (body !== undefined && typeof body !== "string") {
      body = JSON.stringify(body);
      headers.set("Content-Type", "application/json");
    }
    if (token && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    return fetch(url, { method: init.method || "GET", headers, body, credentials: "same-origin" });
  }

  async function read(response) {
    const text = await response.text();
    let data = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (notJson) {
        data = null;
      }
    }
    if (!response.ok) {
      throw new RestError(response.status, data);
    }
    return { data, headers: response.headers, status: response.status };
  }

  /**
   * One request: `path` below the REST root (such as `wp/v2/posts?context=edit`), `init` as fetch's, with a `body`
   * object sent as JSON. Resolves to `{data, headers, status}`; rejects with a `RestError`. An address outside the
   * site's REST API is refused, and nothing is sent without a token.
   */
  async function fetchResponse(path, init = {}) {
    const url = new URL(path.startsWith("/") ? path.slice(1) : path, config.restRoot);
    if (!url.href.startsWith(config.restRoot)) {
      throw new Error(`Not a REST API address of this site: ${url.href}`);
    }
    const token = await auth.getToken();
    if (!token) {
      throw notLoggedIn();
    }
    const response = await send(url, init, token);
    if (response.status !== 401 || new Headers(init.headers || {}).has("Authorization")) {
      return read(response);
    }
    const original = read(response);
    original.catch(() => {});
    try {
      await auth.refresh();
    } catch (refreshFailed) {
      return original;
    }
    const fresh = await auth.getToken();
    if (!fresh) {
      return original;
    }
    const retried = await send(url, init, fresh);
    return retried.status === 401 ? original : read(retried);
  }

  return {
    fetchResponse,
    /** As `fetchResponse`, resolving to the parsed body only. */
    async fetchJson(path, init = {}) {
      return (await fetchResponse(path, init)).data;
    },
  };
}

function hasAuthorization(headers) {
  if (!headers) {
    return false;
  }
  if (typeof Headers !== "undefined" && headers instanceof Headers) {
    return headers.has("Authorization");
  }
  return Object.keys(headers).some((name) => name.toLowerCase() === "authorization");
}

function isUnauthorized(error) {
  return !!error && (error.status === 401 || (!!error.data && error.data.status === 401));
}

/** The path api-fetch builds for a request: `namespace` and `endpoint` joined when both are given, else `path`. */
function requestPath(options) {
  if (typeof options.namespace === "string" && typeof options.endpoint === "string") {
    return `${options.namespace.replace(/^\/+|\/+$/g, "")}/${options.endpoint.replace(/^\/+/, "")}`;
  }
  return typeof options.path === "string" ? options.path : null;
}

/**
 * Where an api-fetch request goes, as its root-URL middleware decides: a request with a path (a string `path`, or
 * `namespace` with `endpoint`) goes to the REST root followed by the path without its leading slash, whatever its
 * `url`; any other request to its `url`. Answers `{url, path}` (`path` true for a path request), or null when the
 * request names no address.
 */
function destination(config, options) {
  try {
    const path = requestPath(options);
    if (path !== null) {
      return { url: new URL(config.restRoot + path.replace(/^\//, "")), path: true };
    }
    if (options.url === undefined || options.url === null) {
      return null;
    }
    return { url: new URL(options.url, document.baseURI), path: false };
  } catch (notAnAddress) {
    return null;
  }
}

/** Whether an address is below the site's REST root (an address with a user name never is). */
function belowRoot(config, url) {
  return url.href.startsWith(config.restRoot);
}

/**
 * The route an api-fetch request goes to below the site's REST root, decided as `bearerMiddleware` decides where it
 * goes: the normalised path below the root, without the query and without leading or trailing slashes (such as
 * `wp/v2/posts/7`); null when the request goes elsewhere or names no address.
 */
export function restRoute(config, options) {
  const target = destination(config, options);
  if (!target || !belowRoot(config, target.url)) {
    return null;
  }
  return target.url.pathname.slice(new URL(config.restRoot).pathname.length).replace(/^\/+|\/+$/g, "");
}

/**
 * The api-fetch middleware that signs the editor's requests to the site's REST API (`config.restRoot`), and only
 * those (a path request whose path leaves the REST root is refused unsent; a `url` elsewhere goes unsigned): adds
 * `Authorization: Bearer <token>` unless the request has an `Authorization` header already; on a 401 renews
 * the token silently and runs the request once more. A failed renewal, or a second 401, rejects with the first error;
 * nothing is retried twice. Without a token the request is not sent: it rejects as not logged in.
 */
export function bearerMiddleware(config, auth) {
  async function signed(options) {
    const token = await auth.getToken();
    if (!token) {
      throw notLoggedIn();
    }
    return { ...options, headers: { ...(options.headers || {}), Authorization: `Bearer ${token}` } };
  }

  return async (options, next) => {
    const target = destination(config, options);
    if (target && target.path && !belowRoot(config, target.url)) {
      // a path is the REST API's by definition: one that climbs out of the REST root is not sent at all
      throw new Error(`Not a REST API address of this site: ${target.url.href}`);
    }
    if (hasAuthorization(options.headers) || !target || !belowRoot(config, target.url)) {
      return next(options);
    }
    const first = await signed(options);
    try {
      return await next(first);
    } catch (error) {
      if (!isUnauthorized(error)) {
        throw error;
      }
      let again;
      try {
        await auth.refresh();
        again = await signed(options);
      } catch (renewalFailed) {
        throw error;
      }
      try {
        return await next(again);
      } catch (second) {
        throw isUnauthorized(second) ? error : second;
      }
    }
  };
}

/**
 * Installs the editor's api-fetch middlewares. `use` puts a middleware in front of the ones already installed, so the
 * preloaded answers are looked up first, then uploads are handled, then the token is added, then the root URL.
 * No nonce endpoint: QuarkPress never asks for a nonce.
 */
export function installApiFetch(config, auth, preload) {
  const apiFetch = window.wp.apiFetch;
  apiFetch.use(apiFetch.createRootURLMiddleware(config.restRoot));
  apiFetch.use(bearerMiddleware(config, auth));
  apiFetch.use(apiFetch.mediaUploadMiddleware);
  apiFetch.use(apiFetch.createPreloadingMiddleware(preload));
}
