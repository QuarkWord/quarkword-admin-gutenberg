# Contributing to QuarkPress Admin Gutenberg

This repository holds the admin screens of [QuarkPress](https://github.com/QuarkPress/quarkpress): a Quarkus
extension that serves an admin shell and WordPress's block editor. It is licensed under the GNU General Public
License, version 2 or later (`LICENSE`), because it redistributes WordPress's built block-editor packages. The
QuarkPress modules it builds on live in the Apache-licensed repository
[QuarkPress/quarkpress](https://github.com/QuarkPress/quarkpress).

## Prerequisites

- JDK 21 or newer (CI runs 21 and 25)
- Maven 3.9 or newer
- Docker or Podman, for the database tests (PostgreSQL and MySQL through Quarkus Dev Services)

## Building

QuarkPress is not on Maven Central yet. Install the QuarkPress modules at the pinned commit first: the parent POM's
`quarkpress.commit` property names it, and CI does the same.

```bash
git clone https://github.com/QuarkPress/quarkpress.git
cd quarkpress
git checkout <quarkpress.commit>
mvn install -DskipTests -pl bom,extensions/rest-wp-v2/deployment,extensions/multisite/deployment,extensions/security/deployment,extensions/posts/deployment,extensions/taxonomy/deployment,extensions/revisions/deployment,extensions/scheduling/deployment,extensions/media/deployment,extensions/media-storage-fs/deployment,extensions/media-image-imageio/deployment,extensions/blocks/deployment,extensions/outbound/deployment -am
```

Then build this repository with `mvn verify` (`-DtestDb=mysql` runs the database tests on MySQL instead of
PostgreSQL). Format with `mvn spotless:apply`; CI runs `spotless:check`. Native executables are built and tested
only in GitHub Actions.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/) with a scope where one fits: `feat(runtime):`,
`feat(deployment):`, `feat(shell):`, `fix(shell):`, `test(it):`, `build:`, `ci:`, `docs:`.

## Design rules

The rules of the QuarkPress repository apply:

1. Quarkus and Java best practices first: build-time over runtime, CDI over globals, typed APIs over strings.
2. Everything is optional; this extension is its own module and needs only the QuarkPress modules it names.
3. Both PostgreSQL and MySQL are first-class.

The admin shell is plain JavaScript (ES modules), CSS and HTML, served as written: no framework, no bundler, no
transpiler, no npm at build time beyond the pinned downloads.

## Source policy

QuarkPress reproduces what WordPress does for its users with an original Java and Quarkus
design. It is never a port of WordPress.

Allowed sources:

- WordPress PHP source code, read to understand how a feature behaves and to build test
  harnesses.
- Public documentation: the REST API handbook, developer reference prose, block grammar
  documentation, WXR samples, role and capability documentation.
- Observed behaviour of a running WordPress instance: HTTP requests and responses, database
  contents of a test install, exported files, and WordPress code run as a black-box oracle in
  tests.
- Third-party specifications and articles describing formats.
- Gutenberg's published npm packages and WordPress's built packages, consumed as dependencies.

This repository is GPL-licensed, so it may redistribute WordPress's built packages, scripts and
styles under the GPL: the build downloads the pinned WordPress release, checks its SHA-256 and
copies the files a checked-in manifest names into the runtime jar. It still ports no PHP:

- No translating or copying of WordPress PHP source code: no line-by-line ports, and no copied
  regular expressions, identifiers or comments. Code follows Java and Quarkus idioms and is
  designed independently.
- No WordPress file is committed, and no file derived from one: the manifests list paths,
  handles, dependency names and version strings only.

When a component must reproduce a WordPress algorithm closely, work in two steps: one person
reads the source and writes a behaviour description in prose with examples and no code; the
implementation is written from that description, and tests against the real WordPress behaviour
confirm it.
