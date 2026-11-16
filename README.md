# QuarkPress Admin Gutenberg

A Quarkus extension that serves QuarkPress's admin screens under each site's admin path (`/wp-admin/`): a small admin
shell (sign-in through OpenID Connect, the posts, pages and media lists, trash and restore, new posts and pages) and the
editor screen, which loads WordPress 7.1.3's own block-editor packages from the jar and mounts
`wp.editPost.initializeEditor` on QuarkPress's WordPress-compatible REST API. The shell is plain JavaScript (ES
modules), CSS and HTML served as written; everything post- and user-specific happens in the browser, over the REST API,
with a Bearer token. It works on a single site and in a network, where every site serves the same pages under its own
path.

Licensed under the GNU General Public License, version 2 or later (`LICENSE`), because the runtime jar redistributes
WordPress's built packages, scripts and styles; see `THIRD-PARTY.md` for the files of other projects it carries. The
QuarkPress modules it builds on are Apache-2.0 and live in
[QuarkPress/quarkpress](https://github.com/QuarkPress/quarkpress).

Status: experimental.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-admin-gutenberg</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

QuarkPress is not on Maven Central yet: install the QuarkPress modules from
[QuarkPress/quarkpress](https://github.com/QuarkPress/quarkpress) at the commit this repository's parent POM names
(`quarkpress.commit`), then this repository's `runtime` and `deployment` modules (see `CONTRIBUTING.md`). The
application imports `quarkpress-bom` (`0.1.0-SNAPSHOT`) and the Quarkus platform BOM 3.40.1, as the QuarkPress modules
do.

### Requirements

- **The QuarkPress modules.** The extension brings `quarkpress-core` and `quarkpress-rest-wp-v2` (the REST API the
  shell and the editor talk to, with what it brings: persistence, security, posts, taxonomy, blocks, outbound). The
  application adds what its sites need and the editor uses when present: `quarkpress-media` with a storage backend
  (`quarkpress-media-storage-fs` or `-s3`) and an image backend (`quarkpress-media-image-imageio`) for the Media
  Library and uploads (without the media module the Media Library screen says "The media module is not installed."),
  `quarkpress-revisions` for revisions and autosaves, `quarkpress-scheduling` for scheduled posts,
  `quarkpress-multisite` for a network, a JDBC driver (`quarkus-jdbc-postgresql` or `quarkus-jdbc-mysql`) and
  `quarkus-rest-jackson`. The test application in `integration-tests` has all of them.
- **An OpenID Connect provider with a public client** for the shell, by default `quarkpress-admin` in the realm the
  application's default OIDC tenant (the security module) accepts Bearer tokens from. In Keycloak:
  - client authentication off (a public client), standard flow (authorization code) on, implicit flow and direct
    access grants off;
  - PKCE with the `S256` method required (`pkce.code.challenge.method`);
  - valid redirect URIs and post-logout redirect URIs: every site's admin folder, `<site URL>/wp-admin/` (the shell
    signs in and out from the admin folder itself: `https://example.com/wp-admin/`, `https://example.com/news/wp-admin/`
    for a subdirectory site);
  - web origins: the sites' origins, needed because the browser calls the token endpoint itself and Keycloak answers
    its cross-origin requests only for the client's web origins (`+` stands for the origins of the redirect URIs);
  - refresh tokens on (Keycloak's default "Use refresh tokens"): the shell renews its access token only with the
    refresh token, since it has no silent-renewal page, so a session lasts until the realm's SSO session idle or
    maximum time ends;
  - the access tokens must pass the application's checks: they must carry the `iss` the server's OIDC discovery
    reports (with a public `quarkpress.admin.oidc.authority`, give Keycloak a fixed `hostname` so that the browser and
    the server see the same issuer, else every Bearer token is refused); the claims the security module reads
    (`preferred_username`, `email`, `given_name`, `family_name`), which Keycloak's default `profile` and `email` client
    scopes give; and, when `quarkus.oidc.token.audience` is set, that audience, through an audience mapper.

  The realm file of the test application (`integration-tests/src/main/resources/quarkpress-admin-dev-realm.json`)
  declares such a client with the redirect URI `*`, which is for Dev Services only.
- **A provider the browser reaches.** The shell talks to the provider from the browser. When
  `quarkus.oidc.auth-server-url` is an address only the server reaches, set `quarkpress.admin.oidc.authority` to the
  provider's public issuer URL.
- **English time-zone names.** The editor's dates come from the REST module's editor route, whose time-zone
  abbreviations need an English locale among `quarkus.locales` (see the REST module's README).

## Configuration

Every key is read at start-up. A key under `quarkpress.admin` that is not listed here stops the application.

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.admin.path` | `/wp-admin/` | The path of the admin screens below each site's path, with a leading and a trailing slash and no empty or dot segment: the lists are at `<site path><path>edit.php`, the editor at `<site path><path>post.php`. It may have more than one segment (`/a/b/`); the pages then reach WordPress's files through one more `../`. WordPress's files stay under `wp-includes/` and `wp-admin/{css,js,images}/` whatever this path is. The path without its trailing slash (`/wp-admin`) is redirected to it (301). The editor's dashboard link and its link to a newer autosave's revision screen lead to the admin folder of the site being edited, whatever `quarkpress.rest-wp-v2.editor.dashboard-url` says. Another value fails the start. |
| `quarkpress.admin.oidc.authority` | the application's `quarkus.oidc.auth-server-url` | The OpenID Connect provider (issuer URL) the shell signs in with. With neither set, the application does not start. |
| `quarkpress.admin.oidc.client-id` | `quarkpress-admin` | The public client the shell signs in as. |
| `quarkpress.admin.oidc.scope` | `openid profile email` | The scopes the shell asks for. |
| `quarkpress.admin.lock-refresh-interval` | `60s` | How often the editor tells the server that its user still holds the post's lock, while the tab is visible (D1). The shell counts whole seconds; under one second the application does not start. |

The editor's other settings (the autosave intervals, the theme features) belong to the REST module:
`quarkpress.rest-wp-v2.editor.*` and `quarkpress.rest-wp-v2.theme.*`, described in its README. Its
`quarkpress.rest-wp-v2.path` (`/wp-json`) is the REST root the shell uses.

## What is served

One Vert.x route answers `GET` and `HEAD` below every site's path (up to four segments deep), each file on its own;
anything else goes on to the application's other routes.

| Path below the site | Answer |
|---|---|
| `wp-admin` | A permanent redirect (301) to `wp-admin/`, the query kept. |
| `wp-admin/`, `wp-admin/index.php` | The shell page: the posts list. |
| `wp-admin/edit.php` | The shell page: the posts list; `?post_type=page` the pages list; `post_status=`, `paged=` and the trash notice's `trashed=`, `untrashed=`, `deleted=`, `ids=` as in WordPress. |
| `wp-admin/upload.php` | The shell page: the Media Library list. |
| `wp-admin/post.php?post=<id>&action=edit` | The editor page, for any post type the REST API serves. |
| `wp-admin/post-new.php[?post_type=<type>]` | The editor page: it creates the draft (`POST /quarkpress/v1/drafts`), puts `post.php?post=<id>&action=edit` in the address bar and opens it. |
| `wp-admin/revision.php` | The editor page, showing a notice that points back to the editor (D9). |
| `wp-admin/quarkpress-admin/config.json` | The shell's settings: `adminPath`, `restPath`, `oidc` (`authority`, `clientId`, `scope`), `lockRefreshInterval` in seconds and the `wordpress` version. The same answer for every site and user. |
| `wp-admin/quarkpress-admin/<file>` | The shell's own files (`app.js`, `editor.js`, `shell.css`, `screens/…`, `editor/…`, `l10n/…`), only those `shell/shell-files.txt` lists. |
| `wp-admin/quarkpress-admin/vendor/oidc-client-ts.js` | The vendored sign-in client (oidc-client-ts's browser bundle), and its `oidc-client-ts.LICENSE`. |
| `wp-includes/…`, `wp-admin/css/…`, `wp-admin/js/…`, `wp-admin/images/…` | WordPress's files, in WordPress's own layout, only those `wordpress-files.txt` lists; any other path there answers 404. |

(`wp-admin/` stands for `quarkpress.admin.path`.) A path with a `.` or `..` segment, a segment starting with a dot, an
encoded dot, a backslash or an empty segment is answered by none of these.

- **The two pages** are rendered once, at start-up, from `wordpress-handles.json`: WordPress's script and style
  handles with their dependencies, versions and inline scripts, printed in WordPress's dependency order (the order of
  WordPress 7.1.3's own editor page), never as hand-written tags. A broken registry fails the start. The pages are the
  same for every site, post and user (D13) and name every file by a page-relative URL (`../wp-includes/…`, D14). They
  are sent with `X-Frame-Options: SAMEORIGIN`, `Content-Security-Policy: frame-ancestors 'self'` and
  `Referrer-Policy: strict-origin-when-cross-origin`, as WordPress's admin screens. The shell page loads only the
  shell's stylesheet and scripts and the sign-in client, no WordPress stylesheet (only WordPress's inline
  view-transitions rule, which every admin screen prints); the editor page loads WordPress's
  editor scripts and styles, TinyMCE for the Classic block, the sign-in client and the shell's editor module.
- **Caching.** A file requested with a `ver` query parameter (the pages always add WordPress's) is sent with
  `Cache-Control: public, max-age=31536000, immutable`, since a new release changes the parameter; any other answer,
  the pages and `config.json` included, with `no-cache`. No entity tag is sent. Every answer carries
  `X-Content-Type-Options: nosniff`.
- **The build** downloads `https://wordpress.org/wordpress-7.1.3.zip` and
  `https://registry.npmjs.org/oidc-client-ts/-/oidc-client-ts-3.5.0.tgz`, checks each against the SHA-256 pinned in the
  parent POM (`wordpress.sha256`, `oidc-client-ts.sha256`; wordpress.org publishes only SHA-1 and MD5, so the WordPress
  pin was computed once from the archive and checked against those), keeps them in
  `~/.m2/repository/.cache/quarkpress-admin`, and copies only the files the checked-in lists name into the jar under
  `org/quarkpress/admin/`: `wordpress-files.txt` (about 170 of WordPress's files under `wp/`, D7, plus two licence
  files: WordPress's `license.txt`, kept in the jar and never served, and TinyMCE's, served as text beside its files at
  `wp-includes/js/tinymce/license.txt`) and `oidc-client-ts-files.txt` (the browser bundle and its licence under
  `vendor/`, the licence served as text at `wp-admin/quarkpress-admin/vendor/oidc-client-ts.LICENSE`). The copier checks
  the zip's digest again first; another digest or a listed file missing from the download fails the build before
  anything is copied, never a jar with part of the set. The lists hold paths, handles, dependency names and version
  strings only: no WordPress file is in this repository.

### Upgrading WordPress

The handles and versions of a release are data, but only part of that data comes from a manifest:

1. Set `wordpress.version` and `wordpress.sha256` in the parent POM. Compute the SHA-256 of the downloaded zip and check
   the archive against the `.zip.sha1` and `.zip.md5` files wordpress.org publishes beside it.
2. Regenerate the script handles of `wordpress-handles.json` (dependencies and `ver` hashes) from the release's package
   manifest (`wp-includes/assets/script-loader-packages.php`), and the import map and script module from the release's
   module data. A release usually changes most `ver` hashes and may add or drop packages.
3. Re-check against the release what no manifest carries: the style handles and their dependencies, the right-to-left
   list, the legacy scripts the packages depend on, the inline scripts and TinyMCE's version, all kept by hand in
   WordPress's script loader.
4. Update `wordpress-files.txt` (the build names any listed file the zip lacks) and rerun the browser-facts run
   against the new release (the QuarkPress repository's `testing/wp-rest-parity`, profile `capture-har`), then this
   repository's tests: the registry tests hold the recorded tag order of WordPress's page, and the browser flows the
   editor's behaviour.

## Screens

- **Sign-in.** A page without a session shows a "Log In" screen whose "Log in with QuarkPress" button leaves for the
  provider (authorization code with PKCE) and comes back to the page it started from (only a page of the same admin
  folder).
- **Posts and Pages** (`edit.php`, `edit.php?post_type=page`): title and date columns, the status views All,
  Published, Scheduled, Drafts, Pending, Private and Trash with their counts (views with none are hidden), paging by 20,
  "Add New Post" / "Add New Page", and the row actions "Move to Trash", "Restore" and "Delete Permanently". Moving to
  the trash shows WordPress's "… moved to the Trash." notice with "Undo"; restoring puts the post back as a draft, as
  WordPress does by default since 5.6. A user who may not list a status sees "Sorry, you are not allowed to access this
  page."
- **Media Library** (`upload.php`): the attachments as a list with paging.
- **The editor** (`post.php?post=<id>&action=edit`, `post-new.php`): WordPress's block editor with the settings,
  preloaded answers, block definitions and date settings of the REST module's editor route, the post lock, previews,
  uploads, autosaves, revisions, scheduling and publishing as WordPress's editor does them. A post that does not exist,
  that the user may not edit or that is in the trash gets WordPress's message ("You attempted to edit an item that does
  not exist. Perhaps it was deleted?", "Sorry, you are not allowed to edit this item.", "You cannot edit this item
  because it is in the Trash. Please restore it and try again.") instead of the editor; a session that cannot be
  renewed shows "Your session has expired. You can log in again from this page or go to the login page." over the
  editor and locks saving.
- **Log Out** in the shell's header closes the user's preview sessions on the site (D19) and signs out at the provider.

Not there: the dashboard, users, settings, comments, plugins, themes, tools and profile screens; in the lists, search,
bulk actions, Quick Edit, the author, category, tag and comment columns, the "Mine" and "Sticky" views (D20), the media
grid and its row actions. The shell has no menu beyond "All Posts", "All Pages" and "Media Library".

## Security notes

- Tokens stay in the tab and the windows it opens: the user and the sign-in state live in memory and `sessionStorage`,
  never in `localStorage` or a cookie; a tab's session ends with the tab. A window the tab opens starts with a copy of
  its `sessionStorage`, as browsers do for `window.open`: the editor's preview window, which renders the site's front
  end, holds the signed-in user's access and refresh tokens too (checked in Chromium by the preview flow), readable by
  any script of the site's origin running there. The provider's refresh token (the only renewal the shell uses) renews
  the access token a minute before it expires (D10); that renewal and the one after a refused request share one token
  request, so the refresh token is never sent twice at once and Keycloak's "Revoke Refresh Token" can stay on.
- REST calls carry `Authorization: Bearer …` only when their URL is below the site's own REST root; no other request
  gets the token. Without a token, nothing is sent.
- There is no nonce and no login cookie. The only cookie is the preview session (`quarkpress_preview`, `HttpOnly`,
  `SameSite=Lax`, the site's path), which only the front end's preview pages read; the REST API never accepts it as a
  credential.
- The sign-in comes back only to a page of the same admin folder, and the shell only ever rewrites the address bar to a
  file of that folder.
- The post lock is released on leaving the page by a request without an access token that carries the lock's one-time
  release token (the browser's beacon still sends the site's cookies, the preview cookie among them; no REST route
  reads it).
- The pages may be framed only by their own site; the shell's and WordPress's files are served only when a list names
  them.

## Tests and CI

- `runtime`: unit tests of the copier (digest, missing files, the lists agreeing), the paths, the files and the handle
  registry (WordPress's recorded tag order).
- `deployment`: `QuarkusExtensionTest` tests of the configuration, the served files and headers, the two pages and the
  settings answer, against PostgreSQL or MySQL through Dev Services (`*DbTest`).
- `integration-tests` (never published): a test application with every QuarkPress module the admin uses, Keycloak and
  the database through Dev Services, and Playwright for Java 1.63.0 driving headless Chromium. `AdminFlowsDbTest` runs
  the sign-in, lists, trash and restore, editor, lock, preview and degradation flows, each as one named user in its own
  browser context, waiting on requests, editor state and the shell's own promises, never on elapsed time.
  `AdminSmokeIT` runs a short flow against the packaged application, and against the native executable in CI.
- `.github/workflows/ci.yml`: JDK 21 and 25 × PostgreSQL and MySQL. It checks out QuarkPress/quarkpress at the
  parent POM's `quarkpress.commit` (into `.quarkpress/`, ignored by git) and installs the modules this repository uses,
  installs the admin modules, installs Playwright's Chromium (cached by the Playwright version), runs the extension's
  tests and then the browser flows in a Maven run of their own, and fails when the flows did not run; `spotless:check`
  on one cell.
- `.github/workflows/native.yml`: the same pins, then a Mandrel container build of the test application and the
  smoke flow against the native executable, on PostgreSQL and MySQL.

The monorepo pin moves with every QuarkPress change the admin needs: the commit is pushed to QuarkPress/quarkpress
first, then `quarkpress.commit` names it.

## Differences from wp-admin

The labels are stable; withdrawn labels (D8, D11, D15) are not reused.

| Label | Difference |
|---|---|
| D1 | No admin-ajax and no heartbeat: the post lock is refreshed through `/quarkpress/v1/locks` every 60 s (`quarkpress.admin.lock-refresh-interval`) while the tab is visible and at once when it becomes visible again (WordPress's heartbeat: every 10 s while focused, 120 s while blurred), released on leaving the page with a one-time token (WordPress 7.1.3 never releases it, so a closed tab keeps the lock for up to 150 s), and the session check is OIDC token renewal with the shell's own overlay. |
| D2 | The Classic block edits with TinyMCE as in WordPress (content that is not block-delimited opens as one Classic block), with its inline link toolbar; but the classic-editor pieces that need admin-ajax or the classic edit screen are not shipped: the link dialog's script (`wplink`, whose search is an admin-ajax call) and the jQuery UI menu and autocomplete it uses, the text-mode toolbar (`quicktags`), the Thickbox media popup (`thickbox`, `media-upload`), the classic shortcode views (`mce-view`) and the legacy `shortcode` script (the `@wordpress/shortcode` package ships). What needs them, the classic link dialog with its search and the classic media popup, is unavailable. |
| D3 | No theme styles in the canvas until QuarkPress has themes: the editor settings carry no `styles`, so the canvas uses the editor's default styles, the block CSS, the classic-theme styles and the preset variables derived from the editor settings. |
| D4 | The media library is the editor's REST-based DataViews modal (`__experimentalDataViewsMediaModal`), not the Backbone `wp.media` modal; the legacy media scripts are loaded as dependencies with neutralised settings. |
| D5 | Draft previews rely on the preview session cookie, opened before the editor mounts and renewed before it expires, which tells the front end who is previewing (a preview window carries no token); published-post previews carry the preview token. |
| D6 | en_US only: no language packs and no translation tags; `wp.i18n` holds only the text direction, and the date texts are English. |
| D7 | Only the files the block editor needs are shipped (about 170 of WordPress's files, minified where WordPress ships a minified file, with the right-to-left stylesheets, including the TinyMCE files the Classic block loads); no unminified siblings except `default-editor-styles.css`, whose text the editor uses, no admin-chrome stylesheets beyond those the editor screen needs, no block front-end CSS, no WASM workers and no TinyMCE language files. |
| D9 | `revision.php?revision=` shows a notice pointing back to the editor ("Revisions are shown inside the editor: open the post and choose the revision."), since revisions are viewed inside the editor; `post.php` without `action=edit` shows the not-found message, and the other admin files (`users.php`, `options-general.php`, `admin-ajax.php`…) are not served. |
| D10 | Sign-in is OIDC authorization code with PKCE (a public client, tokens in memory and `sessionStorage`); REST calls carry a Bearer token, never a cookie or nonce; a 401 is answered by one silent renewal and one retry. |
| D12 | The editor's `comments?type=note` request answers 404 (notes are not implemented); the editor tolerates it and shows no notes; the block menu's "Add note" cannot save one. |
| D13 | The pages are served statically and identically for every post: everything post-specific is fetched by the browser with the Bearer token; the page carries no nonce, no user id and no preload. |
| D14 | Asset URLs are page-relative (`../wp-includes/…`), so one page serves every site of a network; WordPress prints absolute site URLs. |
| D16 | After a takeover, the tab that lost the lock autosaves once, freezes saving and shows "Someone else has taken over this post"; WordPress 7.1.3 keeps refreshing and autosaves on every 10 s tick. |
| D17 | An expired token is renewed once and the request retried once, then the error is shown (a failed renewal sends nothing and shows the session overlay); WordPress's nonce refresh retries without a bound while the server keeps refusing, with no notice. |
| D18 | The editor's first wave of reads asks for the theme's base global styles, their variations and the front page's template lookup (`/wp/v2/global-styles/themes/<stylesheet>`, `…/variations`, `/wp/v2/templates/lookup?slug=front-page`), which QuarkPress does not serve until it has a theme engine; their 404s end that wave, so the second wave never runs and api-fetch logs "Some preloads were never consumed" with the post author's preloaded read, where WordPress logs "All preloads consumed.". The editor works as before. |
| D19 | Log Out closes every preview session of the user on that site, whichever browser opened it, before signing out (stricter than WordPress, whose logout ends only the login session it was made from: the user's other browsers stay signed in); preview cookies of the user's other sites expire on their own; leaving the editor does not close the session (like WordPress's login cookie, it outlives the tab). |
| D20 | The posts and pages lists have no "Mine" and no "Sticky" views; wp-admin shows "Mine" when other authors have posts too and "Sticky" when there are sticky posts. |
| D21 | Opening WordPress's take-over address (`post.php?post=<id>&action=edit&get-post-lock=1`) only opens the post: when another user holds the lock, the "This post is already being edited" dialog is shown and the lock is taken over only by its "Take over" button, since no nonce can prove the link was printed for the user. WordPress takes over on such a link when its nonce is valid and refuses it without one. |
| D22 | The lock dialogs show WordPress's texts without the "(preview↗)" link to the other user's version of the post. |
| D23 | The theme's `wp-block-styles` support is not reflected in the canvas: WordPress adds the block library's theme stylesheet to the editor's iframe when the theme declares it (`quarkpress.rest-wp-v2.theme.wp-block-styles`); QuarkPress ships no such stylesheet until it has themes. |
| D24 | The vendored sign-in client ends with a `sourceMappingURL` comment whose map is not shipped: with the browser's developer tools open, the request for `oidc-client-ts.js.map` answers 404. The file is kept byte-identical to the published package. |
