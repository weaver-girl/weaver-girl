# Publishing the artifacts

Three ways to make `io.github.cc11001100` artifacts resolvable were measured against this
repository on 2026-11-25. The short version: **Maven Central** is the destination, **JitPack**
is the bridge that exists today, and **a GitHub repository used as a Maven repository** is not
the free hosting it looks like.

Everything below is measured, not assumed. The measurements are reproducible with the commands
quoted.

## Maven Central — the destination

The build is already wired for it and nothing is missing except credentials. What stands in the
way today is not engineering:

| State | Evidence |
| --- | --- |
| `v1.0.0` GitHub Release published | six signed assets, `2026-11-24` |
| Central job ran | `skipped` — `CENTRAL_USERNAME` / `CENTRAL_TOKEN` are not set |
| Artifacts on Central | `https://repo1.maven.org/maven2/io/github/cc11001100/` → 404, and the Central search API returns `numFound: 0` |

The job is `maven-central` in `.github/workflows/release.yml`, gated on
`needs.preflight.outputs.has_central`, and it runs `./mvnw -Pquality-gate,release clean deploy`.
Nothing else has to be written. Set the four secrets described in
[`RELEASE_PROCEDURE.md`](../RELEASE_PROCEDURE.md) and re-cut the tag.

**Why it is the destination rather than the fallback:**

- It is the default. Every Java engineer, every IDE auto-import, every corporate mirror, every
  SCA tool reads it. Anything else needs a `<repository>` block in the consumer's POM.
- It is not a GitHub dependency for the consumer. The build already depends on GitHub for
  Actions; artifact resolution does not have to depend on it too.

**What it costs.** At 1.0 it means publishing the full reactor: 153 modules × (jar + sources +
javadoc + a `.asc` for each) ≈ 1,000 files per version. The measured uncompressed total for one
version, built locally, is **89 MB** (71 MB of jars, 17.8 MB of sources and javadocs). Central
absorbs that; that is what it is for. Note the javadoc jars: `maven-javadoc-plugin` is bound
globally with `failOnError=true`, so a javadoc regression in any of the 153 modules blocks the
whole Central deploy. The GitHub Release half is unaffected — it does not build javadocs per
module.

## JitPack — the bridge that works today

JitPack builds a repository from a git ref **on demand** and serves the result as Maven
coordinates, no account, no credentials, no upload step.

**Measured, end to end:**

```xml
<repository>
  <id>jitpack</id>
  <url>https://jitpack.io</url>
</repository>
```

```xml
<dependency>
  <groupId>com.github.weaver-girl.weaver-girl</groupId>
  <artifactId>weaver-girl-api</artifactId>
  <version>v1.0.0</version>     <!-- any tag or commit; main-SNAPSHOT follows main -->
</dependency>
```

- `v1.0.0` and `main` both report `BUILD SUCCESS` on JitPack, and
  `weaver-girl-api/v1.0.0/weaver-girl-api-v1.0.0.pom` returns 200.
- A clean probe project resolved `com.github.weaver-girl.weaver-girl:weaver-girl-api:v1.0.0`
  and its transitive dependencies, outside this repository's own classpath.
- The first attempt **failed in 25 s** on
  `Required Java version 11 is not met by current version: 1.8.0_292` — JitPack's default JDK.
  [`jitpack.yml`](../jitpack.yml) pins `openjdk17` and skips the verify-phase quality gate,
  which already runs in this repository's CI on every push. Verified locally: the exact
  install command from that file is `BUILD SUCCESS` in 2m13s across the whole reactor.

**What it changes for a consumer:** the group id. JitPack is `com.github.weaver-girl.weaver-girl`
and `com.github.weaver-girl` — **not** `io.github.cc11001100`. Two sets of coordinates for the
same artifact is a decision to make deliberately, not something to end up with by accident.

**Why it is not the destination:** JitPack is third-party infrastructure with no SLA, builds
happen lazily on first request (a consumer's first build can wait minutes, or fail), and the
artifact it produces is built from a git ref rather than from a signed release that passed the
gate. It is a good answer to "make it resolvable today".

## A GitHub repository as a Maven repository

This is the option in the question, and it does not give what it appears to give. There are two
distinct things people mean by it:

### GitHub Packages (`maven.pkg.github.com`)

Publishing works, and it is well supported by Actions. **Consuming does not come for free.**
GitHub's documentation is explicit: *"You need an access token to publish, install, and delete
private, internal, and public packages."* For a public package that still means every consumer
needs a `settings.xml` with `GITHUB_TOKEN` or a PAT. Measured here:
`https://maven.pkg.github.com/weaver-girl/weaver-girl/` answers `401` to an anonymous request.

For an open-source hook base whose pitch is "add one jar", requiring a GitHub token before a
single dependency resolves is a worse first-contact experience than not publishing at all.

### A plain repository served as static files

The genuinely free version: deploy a Maven layout (`group/artifact/version/...`) to
`raw.githubusercontent.com` or GitHub Pages, and consumers add that URL as a `<repository>`.
`raw.githubusercontent.com` serves anonymously (measured: 200), so this does work.

It is a worse deal than JitPack and it is manual:

- **No lazy build.** Every version must be built, signed and uploaded by hand — ~1,000 files
  and ~89 MB per version — and no checksum or metadata is generated for you.
- **A ratchet on repository size.** GitHub Pages and repositories have size limits, and a
  repository grows permanently with every version. The 89 MB above is one version of 153
  modules; the cost compounds.
- **Same second coordinate space** as JitPack, with none of JitPack's automation.

It is only worth it if the artifacts must be served from infrastructure the project already
controls and JitPack's third-party dependency is unacceptable. That is not the case here.

## Recommended sequence

1. **Verify the Central namespace and set the four secrets.** This is the only remaining step
   for the destination; the workflow is already written and already skipped for exactly this
   reason. Re-cutting `v1.0.0` then publishes the real coordinates.
2. **Keep JitPack as the interim.** It is already working for any tag or commit, with no
   credentials, so a consumer can resolve the project today while Central waits on the
   namespace.
3. **Do not add GitHub Packages** — it would force a token on consumers and duplicate the
   coordinates a third time.
4. **Do not build a hand-maintained static repository** unless both Central and JitPack become
   unacceptable; per-version cost and repository size make it the worst of the three.

## If Central is unavailable for a while

JitPack already covers "resolvable now". Document the JitPack coordinates next to the Central
ones in the README's dependency section, clearly marked as the interim coordinates, so a reader
is never left guessing which group id to write.
