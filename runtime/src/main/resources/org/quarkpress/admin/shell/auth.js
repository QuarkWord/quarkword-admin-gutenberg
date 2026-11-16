// SPDX-License-Identifier: GPL-2.0-or-later
// Sign-in with OpenID Connect: authorization code with PKCE, through oidc-client-ts (its classic script defines the
// global `oidc` before the shell's modules run). The user and the sign-in state live in sessionStorage, never in
// localStorage: a tab's session ends with the tab.

import { sameFolder } from "./config.js";

/**
 * The shell's sign-in client for a site.
 *
 * @param {{adminUrl: string, oidc: {authority: string, clientId: string, scope: string}}} config
 */
export function createAuth(config) {
  const library = window.oidc;
  const store = () => new library.WebStorageStateStore({ store: window.sessionStorage });
  const manager = new library.UserManager({
    authority: config.oidc.authority,
    client_id: config.oidc.clientId,
    scope: config.oidc.scope,
    redirect_uri: config.adminUrl,
    post_logout_redirect_uri: config.adminUrl,
    response_type: "code",
    userStore: store(),
    stateStore: store(),
    automaticSilentRenew: true,
    loadUserInfo: false,
  });
  let current = null;
  let refreshing = null;

  manager.events.addUserLoaded((user) => {
    current = user;
  });
  manager.events.addUserUnloaded(() => {
    current = null;
  });

  // sign-ins left unfinished leave their state behind
  manager.clearStaleState().catch(() => {});

  /** Where to go after signing in: a page of this site's admin folder, else the admin folder itself. */
  function safeReturn(returnTo) {
    if (returnTo && sameFolder(returnTo, config.adminUrl)) {
      return new URL(returnTo, config.adminUrl).href;
    }
    return config.adminUrl;
  }

  /** Signs in again without the user's help (with the refresh token); rejects when that fails. */
  function refresh() {
    if (!refreshing) {
      refreshing = manager
        .signinSilent()
        .then((user) => {
          if (!user) {
            throw new Error("The silent sign-in gave no user.");
          }
          current = user;
          return user;
        })
        .finally(() => {
          refreshing = null;
        });
    }
    return refreshing;
  }

  return {
    /** Leaves for the sign-in server; back at the admin folder, `completeSignIn` returns to `returnTo`. */
    signIn(returnTo) {
      return manager.signinRedirect({ state: { returnTo: safeReturn(returnTo) } });
    },

    /**
     * Runs first on every page: on the admin folder's address with the sign-in server's answer (`code` or `error`, and
     * `state`) it finishes the sign-in and puts the address back to where the user was; otherwise it reads the stored
     * user. An answer that cannot be used leaves the user signed out.
     */
    async completeSignIn() {
      const url = new URL(location.href);
      const answer =
        url.origin + url.pathname === config.adminUrl &&
        url.searchParams.has("state") &&
        (url.searchParams.has("code") || url.searchParams.has("error"));
      if (answer) {
        let returnTo = config.adminUrl;
        try {
          const user = await manager.signinRedirectCallback(url.href);
          current = user;
          returnTo = safeReturn(user.state && user.state.returnTo);
        } catch (failure) {
          current = null;
          console.warn("QuarkPress admin: the sign-in did not complete.", failure);
        }
        history.replaceState(null, "", returnTo);
        return;
      }
      current = await manager.getUser();
      if (current && current.expired) {
        try {
          await refresh();
        } catch (failure) {
          await manager.removeUser();
          current = null;
        }
      }
    },

    /** The access token, renewed first when it has expired; null when signed out or the renewal fails. */
    async getToken() {
      if (!current) {
        return null;
      }
      if (current.expired) {
        try {
          await refresh();
        } catch (failure) {
          return null;
        }
      }
      return current ? current.access_token : null;
    },

    refresh,

    /** Signs out at the sign-in server too, then comes back to the admin folder. */
    async signOut() {
      await manager.signoutRedirect();
    },

    /** Calls `callback` when the access token expires or its silent renewal fails. */
    onExpired(callback) {
      manager.events.addAccessTokenExpired(() => callback());
      manager.events.addSilentRenewError((error) => callback(error));
    },

    /** The signed-in user (oidc-client-ts's `User`), or null. */
    user() {
      return current;
    },
  };
}
