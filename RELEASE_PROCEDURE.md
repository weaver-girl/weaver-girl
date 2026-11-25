# Release Procedure

Cutting a release of Weaver-Girl attaches the distribution archive (agent jar +
`plugins/`) to a GitHub Release, and — when Central is configured — publishes the
artifacts to Maven Central. The flow is automated by the
`.github/workflows/release.yml` workflow, triggered by pushing a `v*` tag.

The two halves are independent jobs. A GitHub Release does **not** require Central
credentials: the release job verifies and signs the archive and publishes it, while
the Central job is skipped with a warning when `CENTRAL_USERNAME`/`CENTRAL_TOKEN`
are absent. That is deliberate — the archive is what users download, and it should
not be hostage to a publishing account you may not have set up yet.

## Prerequisites (one-time repo setup)

> First time publishing to Central? [`docs/publishing-to-central-tutorial.md`](docs/publishing-to-central-tutorial.md) walks through these prerequisites step by step, including the namespace verification, the token, and how to prove the pipeline before tagging.

### 1. Verify the Maven Central namespace

The artifacts use the group id `io.github.cc11001100`. Maven Central only accepts a
namespace whose ownership has been proven:

- Sign in to <https://central.sonatype.com> **with GitHub as `cc11001100`**. The
  `io.github.<username>` namespace is provisioned automatically for a GitHub sign-in.
- If the account was created with a password instead, add `io.github.cc11001100` under
  *View Namespaces* and complete the GitHub code-hosting verification (create the
  temporary public repository named after the verification key under
  `github.com/cc11001100`, then delete it).
- Do not tag a release until the namespace shows as verified. The deploy step fails
  otherwise, and a failed first deploy leaves the tag pointing at nothing.

The namespace is tied to the `cc11001100` GitHub account, not to the `weaver-girl`
organization. The organization owns the repository and the release workflow; the
namespace proves identity through the maintainer's GitHub sign-in. This is a Central
rule, not a project choice.

### 2. Configure the signing and publishing secrets

The release workflow needs these [repository secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets):

| Secret | Purpose |
|---|---|
| `CENTRAL_USERNAME` | Central Publishing Portal username (token name) |
| `CENTRAL_TOKEN` | Central Publishing Portal access token |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key used to sign artifacts |
| `GPG_PASSPHRASE` | Passphrase for the GPG key above |

Create the Central token at <https://central.sonatype.com> (account → Access Tokens).
Export the GPG key with `gpg --armor --export-secret-keys <keyid>`.

### 3. Release signing key

Releases are signed with a dedicated key, not a maintainer's personal key. The
public half is committed as [`docs/release-signing-key.asc`](docs/release-signing-key.asc):

```
Fingerprint: FB0D 93FF 3C8D 06F2 5048 2583 56EF 998B 96FA 1B31
UID:         Weaver-Girl Release Signing <cc11001100@users.noreply.github.com>
Expires:     2029-11-11
```

`GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` hold the armored private key and its
passphrase. The private key exists only in the repository secrets; rotate it by
generating a new key, setting both secrets, uploading the new public key, and
updating this document. Consumers verify a downloaded artifact with:

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 56EF998B96FA1B31
gpg --verify artifact.jar.asc artifact.jar
```

## Cutting a release

1. **Ensure `main` is green.** CI (`ci.yml`) is what gates the tag: the two
   `Build & Test` jobs plus the quality gate must pass on the commit you tag, and
   `agent-smoke.yml` must pass its six jobs. Locally, the same gate is:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./scripts/release-check.sh
   ```
   (The CI compatibility matrix separately verifies the packaged agent on
   JDK 8/11/17/21.)
2. **Confirm the bench guard passes** (no overhead regression):
   ```bash
   ./scripts/verify-benchmarks.sh
   ```
3. **Update the changelog / version notes** if you keep one.
4. **Tag and push:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
5. The `release.yml` workflow now runs automatically, in three jobs:
   - **Preflight** validates the version (`v1.0.0` → `1.0.0`, rejects SNAPSHOT and
     malformed versions, checks the tag matches) and reports whether Central is
     configured. It has to be its own job because `secrets` cannot be read in a
     job-level `if`.
   - **Build & Publish GitHub Release** sets the Maven version, runs
     `clean verify -Pquality-gate...` (tests are skipped — the tag only exists for a
     commit whose CI gate is already green), attaches `*-sources.jar` +
     `*-javadoc.jar`, signs everything with GPG, packages the agent jar and the
     `plugins/` directory into `weaver-girl-agent-<version>-dist.zip`, verifies the
     manifest and the archive contents, and creates the GitHub Release with the
     archive, the bare jar, and their SHA-256 and `.asc` files.
   - **Publish to Maven Central** runs `-Pquality-gate,release clean deploy` into a
     **staging** deployment. It is skipped when the Central secrets are absent, and
     it runs after the release exists.
6. **Publish from staging** (only when the Central job ran). Log into
   <https://central.sonatype.com>, find the new deployment, and click **Publish**.
   With `autoPublish` configured in the POM deployment, publication usually completes
   without a click; check the Portal either way. Until it is published the artifacts
   are not visible on Maven Central.
7. Verify on Central (may take ~15–30 min to propagate after publishing):
   `https://central.sonatype.com/artifact/io.github.cc11001100/weaver-girl-core`

## Notes

- The compiler targets Java 1.8 bytecode regardless of the JDK building the release;
  the workflow uses JDK 17 only because the release tooling and GPG signing run best
  on it.
- `quality-gate` is **enabled** during `deploy` (`-Pquality-gate,release`): a release
  must pass the same gate as a pull request. Tags only ever point at a commit whose
  gate is green.
- A GitHub Release is published even when the Central job is skipped or fails. The
  release notes say to check the `maven-central` job before pointing a build at the
  coordinates, rather than implying Central is live.
- If you need to re-cut a release, delete the tag locally and remotely, fix what
  failed, re-tag, and push again. `softprops/action-gh-release` updates an existing
  release for the same tag rather than creating a duplicate.
