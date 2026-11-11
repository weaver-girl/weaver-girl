# Release Procedure

Cutting a release of Weaver-Girl publishes artifacts to Maven Central and attaches
the distribution archive (agent jar + `plugins/`) to a GitHub Release. The whole
flow is automated by the `.github/workflows/release.yml` workflow, triggered by
pushing a `v*` tag.

## Prerequisites (one-time repo setup)

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

1. **Ensure `main` is green.** Run the gate on JDK 17 (the CI compatibility matrix
   separately verifies the packaged agent on JDK 8/11/17/21):
   ```bash
   ./scripts/release-check.sh
   ```
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
5. The `release.yml` workflow now runs automatically:
   - Sets the Maven version from the tag (`v1.0.0` → `1.0.0`).
   - Runs `clean deploy -Pquality-gate,release` — the same quality gate as a pull
     request, then attaches `*-sources.jar` + `*-javadoc.jar`, signs them with GPG,
     and uploads them to the Central Publishing Portal into a **staging** deployment.
   - Packages the agent jar and the `plugins/` directory into
     `weaver-girl-agent-<version>-dist.zip`, verifies both artifacts, and creates the
     GitHub Release with the archive, the bare jar, and their SHA-256 files.
6. **Publish from staging.** Log into <https://central.sonatype.com>, find the new
   deployment, and click **Publish**. Until you do this the artifacts are not visible
   on Maven Central. (Auto-publish can be configured on the Portal if you prefer.)
7. Verify on Central (may take ~15–30 min to propagate after publishing):
   `https://central.sonatype.com/artifact/io.github.cc11001100/weaver-girl-core`

## Notes

- The compiler targets Java 1.8 bytecode regardless of the JDK building the release;
  the workflow uses JDK 17 only because the release tooling and GPG signing run best
  on it.
- `quality-gate` is **enabled** during `deploy` (`-Pquality-gate,release`): a release
  must pass the same gate as a pull request. Tags only ever point at a commit whose
  gate is green.
- If the Central deploy fails, the GitHub Release is **not** created (the agent-jar
  verification + release steps come after deploy). Fix the issue, delete the tag
  locally and remotely, re-tag, and push again.
