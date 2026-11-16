# Third-party software

The runtime jar of this extension redistributes files of other projects. They are not part of this repository: the
build downloads them at the pinned versions, checks their SHA-256 and copies the files it needs into the jar.

## WordPress

The built block-editor packages, scripts and styles of WordPress (`wordpress-7.1.3.zip`), under the GNU General Public
License, version 2 or later. The zip's `license.txt` (this notice followed by the full text of the GNU General Public
License, version 2) travels in the jar as `org/quarkpress/admin/wp/license.txt`; it is not served. It opens with this
notice:

```
WordPress - Web publishing software

Copyright 2011-2026 by the contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

This program incorporates work covered by the following copyright and
permission notices:

  b2 is (c) 2001, 2002 Michel Valdrighi - Cafelog

  Wherever third party code has been used, credit has been given in the code's
  comments.

  b2 is released under the GPL

and

  WordPress - Web publishing software

  Copyright 2003-2010 by the contributors

  WordPress is released under the GPL
```

## Vendor files redistributed with WordPress

WordPress ships these files of other projects among the ones the jar carries (paths below the jar's
`org/quarkpress/admin/wp/`, served under each site's path). Several of them reach the jar without their licence
header, which WordPress's build strips; their notices are reproduced here.

| File(s) | Project | Licence | Copyright | Header in the file |
| --- | --- | --- | --- | --- |
| `wp-includes/js/dist/vendor/react.min.js`, `react-dom.min.js` | React 18.3.1 | MIT | Copyright (c) Facebook, Inc. and its affiliates. | yes |
| `wp-includes/js/dist/vendor/react-jsx-runtime.min.js` | React 18.3.1's JSX runtime, built by WordPress | MIT | Copyright (c) Facebook, Inc. and its affiliates. | no |
| `wp-includes/js/dist/vendor/moment.min.js` | Moment.js 2.30.1 | MIT | Copyright (c) JS Foundation and other contributors | no |
| `wp-includes/js/dist/vendor/wp-polyfill.min.js` | core-js 3.39.0 | MIT | Copyright (c) 2014-2024 Denis Pushkarev | yes |
| `wp-includes/js/jquery/jquery.min.js` | jQuery 3.7.1 | MIT | Copyright OpenJS Foundation and other contributors, https://openjsf.org/ | yes |
| `wp-includes/js/jquery/jquery-migrate.min.js` | jQuery Migrate 3.4.1 | MIT | Copyright OpenJS Foundation and other contributors, https://openjsf.org/ | yes |
| `wp-includes/js/jquery/ui/core.min.js`, `mouse.min.js`, `sortable.min.js` | jQuery UI 1.14.2 | MIT | Copyright OpenJS Foundation and other contributors, https://openjsf.org/ | yes |
| `wp-includes/js/underscore.min.js` | Underscore.js 1.13.8 | MIT | Copyright (c) 2009-2022 Jeremy Ashkenas, Julian Gonggrijp, and DocumentCloud and Investigative Reporters & Editors | no |
| `wp-includes/js/backbone.min.js` | Backbone.js 1.6.1 | MIT | Copyright (c) 2010-2024 Jeremy Ashkenas, DocumentCloud | no |
| `wp-includes/js/mediaelement/mediaelement-and-player.min.js` | MediaElement.js 4.2.17 | MIT | Copyright 2010-2017, John Dyer (http://j.hn/) | yes |
| `wp-includes/js/clipboard.min.js` | clipboard.js 2.0.11 | MIT | Copyright (c) Zeno Rocha | no |
| `wp-includes/js/dist/script-modules/latex-to-mathml/index.min.js` | Temml 0.10.34, bundled into WordPress's `latex-to-mathml` module | MIT | Copyright (c) 2020 Ron Kok | no |
| `wp-includes/js/plupload/plupload.min.js` | Plupload 2.1.9 | GPL (unversioned header; WordPress ships the GPL v2 text beside it) | Copyright 2013, Moxiecode Systems AB | no (the zip's unminified `plupload.js` / `moxie.js` carry "Copyright 2013, Moxiecode Systems AB / Released under GPL License."); the GPL v2 text is in WordPress's `license.txt` |
| `wp-includes/js/plupload/moxie.min.js` | mOxie 1.3.5.1 (WordPress maintains it as a de-facto fork) | GPL (unversioned header; WordPress ships the GPL v2 text beside it) | Copyright 2013, Moxiecode Systems AB | no (the zip's unminified `plupload.js` / `moxie.js` carry "Copyright 2013, Moxiecode Systems AB / Released under GPL License."); the GPL v2 text is in WordPress's `license.txt` |
| `wp-includes/js/tinymce/**` | TinyMCE 4.9.11, with WordPress's own TinyMCE plugins (`plugins/wordpress`, `wpautoresize`, `wpeditimage`, `wpemoji`, `wpgallery`, `wplink`, `wpdialogs`, `wptextpattern`, `wpview`) and `skins/wordpress/wp-content.css`, which are WordPress's, under its GPL | LGPL-2.1 (TinyMCE) | Copyright (c) Tiny Technologies, Inc. | separate licence file, see below |
| `wp-includes/fonts/dashicons.*`, `wp-includes/css/dashicons.min.css` | Dashicons, part of WordPress 7.1.3 | GPL-2.0-or-later | the WordPress contributors | no |

The other files under `wp/` (the `@wordpress/*` package builds, the media, Plupload and MediaElement glue scripts,
the stylesheets and images) are WordPress's own, under WordPress's licence above, together with the npm libraries the
package builds bundle, whose notices WordPress keeps inside the files ("Bundled license information").

The MIT-licensed files above (and jwt-decode, under "Other libraries") are distributed under this permission notice,
with the copyright line of their row:

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### TinyMCE

The Classic block edits with TinyMCE 4.9.11, which WordPress ships under the GNU Lesser General Public License,
version 2.1. Its licence text, `wp-includes/js/tinymce/license.txt` from the WordPress zip, travels in the jar beside
the TinyMCE files as `org/quarkpress/admin/wp/wp-includes/js/tinymce/license.txt` and is served at
`<site>/wp-includes/js/tinymce/license.txt`. The TinyMCE files are copied unchanged from the WordPress release, which
ships them minified. The source of TinyMCE 4.9.11 is available from https://github.com/tinymce/tinymce (tag `4.9.11`)
and in WordPress's development repository (https://github.com/WordPress/wordpress-develop).

## Other libraries

| File | Project | Licence | Notice |
| --- | --- | --- | --- |
| `org/quarkpress/admin/vendor/oidc-client-ts.js` (served as `<site>/wp-admin/quarkpress-admin/vendor/oidc-client-ts.js`) | [oidc-client-ts](https://github.com/authts/oidc-client-ts) 3.5.0, its browser bundle (`package/dist/browser/oidc-client-ts.js` of the npm tarball), unchanged | Apache-2.0 | The package's `LICENSE` travels beside it as `org/quarkpress/admin/vendor/oidc-client-ts.LICENSE`, served as text at `<site>/wp-admin/quarkpress-admin/vendor/oidc-client-ts.LICENSE`; the package has no `NOTICE` file. |
| the same file | [jwt-decode](https://github.com/auth0/jwt-decode) 4 (`^4.0.0` in oidc-client-ts's `package.json`), bundled in the oidc-client-ts browser bundle | MIT | `Copyright (c) 2015 Auth0, Inc. <support@auth0.com> (http://auth0.com)`, under the MIT permission notice above; the bundle carries no notice. |

The Apache License 2.0 is compatible with version 3 of the GNU General Public License only, not with version 2.
Where that file is involved, the jar as a combined work is therefore distributed under the terms of the GNU General
Public License, version 3 or later, which this repository's "version 2 or later" licence allows.

## WordPress output copied as data

Some of what WordPress 7.1.3 prints into its editor page is reproduced as data, as a browser receives it, so that the
editor finds the same globals: in `runtime/src/main/resources/org/quarkpress/admin/wordpress-handles.json`, the four
inline scripts around the classic editor and TinyMCE (the `editor` and `wp-editor` scripts' after scripts, the
`getDefaultSettings`/`tinyMCEPreInit` script before the TinyMCE tags and TinyMCE's translation script after them),
with the page's absolute URLs replaced by the shell's TinyMCE base, and the classic editor's settings
(`wpEditorL10n`); in `runtime/src/main/resources/org/quarkpress/admin/shell/l10n/en-us.js`, the English date and
moment locale texts of WordPress's `wp.date` and `moment` settings scripts. They are WordPress's output, under
WordPress's licence above.
