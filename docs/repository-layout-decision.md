# Repository Layout Decision

Status: accepted and implemented, 2026-11-12.

## Context

The project is maintained under the `weaver-girl` GitHub organization. Today the site, the agent,
the public API and all 126 built-in plugins share one repository. The site is already live at
`https://weaver-girl.github.io/weaver-girl/`, built from `website/frontend` by
`.github/workflows/pages.yml`. Two private repositories created in September
(`weaver-girl-website-frontend`, `weaver-girl-website-backend`) are empty placeholders and are not
part of any build.

## Decisions

### 1. The website is its own repository

The site is a static React + Vite application. It shares no sources, no build tooling and no release
cadence with the Java reactor. Keeping it in the reactor means every site change checks out and
builds next to a 126-module Maven project, and every contributor to the agent carries the site's
dependency tree.

New repository: `weaver-girl/weaver-girl.github.io` (public — GitHub Pages for an organization on
the free plan requires a public repository). Naming it `<org>.github.io` publishes it at the domain
root, `https://weaver-girl.github.io/`, instead of a `/<repo>/` subpath. The Pages workflow derives
`BASE_PATH` from the repository name, so either layout builds correctly.

The authoritative engineering documentation stays in this repository under `docs/`. The site's
`src/data/content.ts` is curated copy, not a second source of truth; anything it quotes must come
from `docs/`.

### 2. Built-in plugins stay in this repository

The runtime is already modular: each plugin is a separate jar discovered through `ServiceLoader` and
loaded from the agent's `plugins/` directory. Splitting the build as well would buy nothing and cost
a lot — 126 repositories, 126 CI pipelines, and a version skew problem across artifacts that are
released together and tested together by `weaver-girl-plugins-tests`. Elastic APM and
OpenTelemetry Java Agent, the closest projects, keep their built-in plugins in-tree for the same
reason.

### 3. Third-party plugins get a template repository

The plugin developer guide describes the required files but there is no starting point to copy.
`weaver-girl/weaver-girl-plugin-template` is a standalone Maven project (not a reactor module) that
compiles against `weaver-girl-api` as `provided`, registers through `META-INF/services`, carries the
`weaver-girl-plugin.properties` contract metadata, and includes a `weaver-girl-testkit` test plus a
runnable target for an agent attach. It is marked as a GitHub template repository.

### 4. User-facing examples wait for the first release

`weaver-girl-sample` stays in-tree: it is an integration test of the current reactor and must track
unreleased code. A separate `weaver-girl-examples` repository should only appear after `1.0.0` is on
Maven Central, because its value is that it depends on *released* artifacts and proves the user
experience rather than the build. One repository with one folder per scenario (servlet + JDBC,
Dubbo, Spring Boot, Kafka) is preferable to a repository per plugin.

## Migration (done 2026-11-12)

1. Created `weaver-girl/weaver-girl.github.io` (public) and pushed the extracted history, all 13
   website commits preserved. Pages builds with GitHub Actions and serves the domain root.
2. Verified the root URL and every route (`/quick-start`, `/cases`, `/plugins`, `/architecture`,
   `/docs`), including the hashed JS asset.
3. Removed `website/` from this repository. `.github/workflows/pages.yml` now only publishes a
   redirect from the old `/weaver-girl/` project-site path to `https://weaver-girl.github.io/`,
   preserving the route; `site-redirect/index.html` is the redirect page. Remove both after one
   release.
4. Created `weaver-girl/weaver-girl-plugin-template` (public) and marked it as a GitHub template
   repository.
5. The two September placeholders (`weaver-girl-website-frontend`, `weaver-girl-website-backend`)
   were archived. Deleting them needs the `delete_repo` token scope:
   `gh auth refresh -h github.com -s delete_repo`, then `gh repo delete <name> --yes`.

## Consequences

- Site changes no longer trigger the Java CI, and Java changes no longer trigger a site deploy.
- The site and the docs can drift; the decision above makes `docs/` authoritative and the site
  curated.
- Third-party plugins start from a working build instead of a prose checklist.
- Built-in plugins keep one version and one test matrix.
