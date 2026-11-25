# Publishing to Maven Central, step by step

A walkthrough for a first-time publisher. Every command and every expected output below was run
against this repository; where something could not be run here (it needs the account), it says
so explicitly.

Read this once end to end before starting. The order matters: the namespace must be verified
**before** you tag, because a deploy to an unverified namespace fails and burns the tag.

## What is already done

Most of the work is done. Do not redo it:

| Piece | State |
| --- | --- |
| Group id, license, SCM, developers in the POM | done — Central requires all of these and rejects a POM missing any |
| `-Prelease` profile (GPG signing + Central plugin) | done, `pom.xml` |
| Release workflow with a Central job | done, `.github/workflows/release.yml` |
| GPG key pair; public half committed | done, `docs/release-signing-key.asc`, fingerprint `56EF998B96FA1B31` |
| `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` repository secrets | **already set** (verified 2026-11-25) |
| `CENTRAL_USERNAME`, `CENTRAL_TOKEN` repository secrets | **missing** — this is the blocker |
| Namespace `io.github.cc11001100` verified on Central | unknown; confirm in step 1 |
| Sources and javadoc for all 153 modules | verified: 0 javadoc errors, 298 jars built |
| Samples excluded from publication | done — `weaver-girl-sample` and `weaver-girl-plugins-tests` set `maven-deploy-plugin.skip` |

Two things about this project that a generic tutorial would get wrong:

- **The namespace is tied to the GitHub account `cc11001100`, not to the `weaver-girl`
  organization.** Central's `io.github.<user>` rule keys off the account that signs in. The
  organization owns the repo; the maintainer's account proves the namespace.
- **Publishing is 153 modules at once** (jar + sources + javadoc + `.asc` each). A first
  publish is not one artifact, it is a bundle of roughly a thousand files.

## Step 1 — Verify the namespace

Sign in at <https://central.sonatype.com> **using GitHub as `cc11001100`**, not with an email
and password.

Then open your account menu (top right) → **View Namespaces**.

- **If `io.github.cc11001100` is listed as verified, you are done with this step.** A GitHub
  sign-in provisions `io.github.<username>` automatically.
- **If the list is empty, or shows `io.github.cc11001100` as `Unverified`,** do the manual
  proof:
  1. **Add Namespace**, enter `io.github.cc11001100`, submit.
  2. The request appears as *Unverified*. Copy its **Verification Key** (clipboard icon).
  3. Create a **temporary public repository** under `github.com/cc11001100` whose name is
     exactly that verification key.
  4. Back in the Portal: **Verify Namespace** → **Confirm**. It moves to *Verification
     Pending*.
  5. Wait a few minutes, refresh. Once it shows registered, **delete the temporary
     repository**.

**Do not skip ahead to a tag.** Publishing to an unverified namespace fails.

## Step 2 — Generate a publishing token

Open <https://central.sonatype.com/usertoken> → **Generate User Token** → give it a name
(something like `weaver-girl-release`) and an expiry.

The dialog shows a **username** and a **password**. Copy both immediately — they cannot be
retrieved after the dialog closes.

> The Portal's own docs do not spell out which field is which. In practice the generated
> username looks like a short token id and the password is the long secret. Put the **username**
> in `CENTRAL_USERNAME` and the **password** in `CENTRAL_TOKEN`. If a deploy comes back
> `401`, swap them — that is the only thing wrong when the credentials are otherwise valid.

## Step 3 — Put the two credentials in the repository

The repository is `weaver-girl/weaver-girl`; the secrets live there, not on your account.

```bash
gh secret set CENTRAL_USERNAME --repo weaver-girl/weaver-girl
gh secret set CENTRAL_TOKEN    --repo weaver-girl/weaver-girl
```

Each command prompts for the value (paste it, then Enter). Verify:

```bash
gh secret list --repo weaver-girl/weaver-girl
```

Expected — four secrets, in any order:

```
GPG_PASSPHRASE    <date>
GPG_PRIVATE_KEY   <date>
CENTRAL_USERNAME  <date>
CENTRAL_TOKEN     <date>
```

> The token has an expiry. When it expires, the release job starts failing with `401` and the
> fix is to generate a new token and set `CENTRAL_TOKEN` again. Worth a calendar entry.

## Step 4 — Prove the pipeline without publishing

Before a real run, do one that signs and packages but publishes nothing. This catches a
GPG or javadoc problem in minutes instead of after a tag has already burned.

```bash
gh workflow run release.yml --repo weaver-girl/weaver-girl \
  -f version=1.0.1 -f dry_run=true
gh run watch --repo weaver-girl/weaver-girl
```

With `dry_run=true` the *GitHub Release* job builds, signs and uploads nothing public, and the
*Maven Central* job is skipped. Read the log and confirm:

- `Build and sign the artifacts` ends in `BUILD SUCCESS`
- `=== Checksums ===` lists two files
- `gpg --verify` prints `Good signature` twice (the archive and the bare jar)

### What a publish actually sends

The Central job runs `./mvnw -Pquality-gate,release clean deploy`. Two properties in the
Central plugin decide what happens after the bundle is built:

| Property | Value here | Effect |
| --- | --- | --- |
| `autoPublish` | `true` | Central publishes as soon as validation passes; no click needed |
| `waitUntil` | `VALIDATED` | the build fails on a validation error instead of publishing something broken |

To rehearse the whole reactor without uploading anything:

```bash
./mvnw -Prelease deploy -DskipTests \
  -Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true \
  -Dgpg.skip=true -DskipUpload=true
```

`skipUpload` stages and bundles every module, then stops before contacting Central, so **no
credentials are needed** — confirmed here: the command above finished `BUILD SUCCESS` across all
153 modules with no `CENTRAL_*` set, and without any network call to Central.

Two caveats about that rehearsal, both measured here:

- **Do not add `-DskipPublishing=true` to it.** `skipPublishing` excludes a module's artifacts
  from the publication altogether, and applied to every module there is nothing left to bundle.
  The run still prints `BUILD SUCCESS`, which makes it look like a rehearsal that passed when
  it exercised nothing. `skipUpload` alone is the flag you want.
- **This is not a dry run of Central's validation.** Nothing is uploaded, so the namespace,
  the token and Central's metadata checks are not exercised. It proves the build, the
  sources/javadoc jars and the signing, and nothing more.

The real rehearsal that does exercise Central end to end is a different tactic: publish a
**throwaway version** under the same namespace and look at the result, then tag the version you
actually want. A published version is permanent, so the throwaway has to be a version number
you are happy to leave in the index — for this project that means `1.0.0` is out (see step 5)
and you would publish e.g. `0.0.1` deliberately. If that is not acceptable, the dry run above
plus careful reading of Central's validation report on the first real deploy is the alternative.

## Step 5 — Tag the release

Only after steps 1–4 are green.

```bash
git switch main && git pull
git tag v1.0.1
git push origin v1.0.1
```

Pick the next free version. **`1.0.0` is already burned**: it was tagged earlier, and

- the GitHub Release for it exists, and
- JitPack has a **failed** `1.0.0` build cached against the bare name, and
- Maven Central never received it.

Re-publishing under `1.0.0` would be confusing on two counts, so use `1.0.1` (or
`1.0.0-rc.2` if you want to rehearse the real path before the stable number).

The push triggers `release.yml`:

- **Preflight** — checks the tag matches the version, rejects `SNAPSHOT`, reports whether
  Central is configured.
- **Build & Publish GitHub Release** — signs, packages, publishes the archive.
- **Publish to Maven Central** — runs `-Pquality-gate,release clean deploy`.

## Step 6 — Watch the deploy in the Portal

Open <https://central.sonatype.com> → **Deployments**. The deployment goes through:

| State | Meaning |
| --- | --- |
| `Publishing` | bundle received, being validated |
| `Validated` / `Validating` failing → `Failed` | validation result; on `Failed` the card shows *Validation Results* with the exact reason |
| `Published` | live on Maven Central |

If it sits in `Validated` and does not advance, press **Publish** on the card. With
`autoPublish: true` it normally advances on its own, but the click is always the fallback.

**Declining to publish is allowed.** A validated deployment can be left alone or **Drop**ped.
Deployments visible on the Deployments tab are **cleaned up after 90 days**, so an unpublished
deployment does not stay forever.

## Step 7 — Verify it is actually there

Maven Central propagates within roughly 15–30 minutes. Check all three:

```bash
# 1. The directory exists at all
curl -s -o /dev/null -w '%{http_code}\n' \
  https://repo1.maven.org/maven2/io/github/cc11001100/weaver-girl-api/

# 2. The version's files
curl -s https://repo1.maven.org/maven2/io/github/cc11001100/weaver-girl-api/maven-metadata.xml

# 3. The search index (lags the files; not proof of failure if empty)
curl -s 'https://search.maven.org/solrsearch/select?q=g:%22io.github.cc11001100%22&rows=20&wt=json'
```

Then the real test — resolve it from a project that knows nothing about this repository:

```bash
mkdir -p /tmp/consume && cd /tmp/consume
cat > pom.xml <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>probe</groupId><artifactId>probe</artifactId><version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.github.cc11001100</groupId>
      <artifactId>weaver-girl-api</artifactId>
      <version>1.0.1</version>
    </dependency>
  </dependencies>
</project>
POM
rm -rf ~/.m2/repository/io/github/cc11001100
mvn -B dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
grep -o 'weaver-girl-api[^:]*jar' /tmp/cp.txt
```

No `<repositories>` block: if this resolves, the artifact is genuinely on Central.

Also confirm the signature, since Central requires it and consumers may check it:

```bash
curl -O https://repo1.maven.org/maven2/io/github/cc11001100/weaver-girl-api/1.0.1/weaver-girl-api-1.0.1.jar.asc
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 56EF998B96FA1B31
gpg --verify weaver-girl-api-1.0.1.jar.asc weaver-girl-api-1.0.1.jar
```

## When it fails

The failures you are most likely to hit, in the order they usually appear:

| Symptom | Cause | Fix |
| --- | --- | --- |
| `401 Unauthorized` from Central | wrong token, or the token expired | regenerate at `/usertoken`, `gh secret set CENTRAL_TOKEN` |
| `namespace not verified` / `403` | step 1 not done, or done under the wrong GitHub account | verify `io.github.cc11001100` as `cc11001100` |
| `Invalid signature` in validation | GPG key mismatch | confirm `docs/release-signing-key.asc` matches the private key in `GPG_PRIVATE_KEY` |
| `Build and sign` fails on javadoc | a javadoc error in some module | javadoc is `failOnError=true`; fix the warning-as-error or `-Dmaven.javadoc.skip=true` for that module only |
| `Missing required metadata` | a new module without `url`, `licenses`, `scm`, `developers` | copy those blocks from the parent POM |
| deployment stuck in `Validated` | `autoPublish` did not fire | press **Publish** in the Portal |

Re-cutting: delete the tag locally and remotely, fix, re-tag, push again. A published version
cannot be republished or withdrawn — **Central is permanent**, which is why step 4 exists.

```bash
git tag -d v1.0.1
git push origin :refs/tags/v1.0.1
```

## The one thing to remember

**Nothing is changed by publishing until the version number has never been published before.**
Pick a fresh version, and the rest is reversible: a failed deploy can be dropped, a bad tag can
be deleted. Only a *published* version is permanent.
