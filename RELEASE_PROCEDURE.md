# Release Procedure

Cutting a release of Weaver-Girl publishes artifacts to Maven Central and attaches
the packaged agent jar to a GitHub Release. The whole flow is automated by the
`.github/workflows/release.yml` workflow, triggered by pushing a `v*` tag.

## Prerequisites (one-time repo setup)

The release workflow needs these [repository secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets):

| Secret | Purpose |
|---|---|
| `CENTRAL_USERNAME` | Central Publishing Portal username (token name) |
| `CENTRAL_TOKEN` | Central Publishing Portal access token |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key used to sign artifacts |
| `GPG_PASSPHRASE` | Passphrase for the GPG key above |

Create the Central token at <https://central.sonatype.com> (account → Access Tokens).
Export the GPG key with `gpg --armor --export-secret-keys <keyid>`.

## Cutting a release

1. **Ensure `main` is green.** On JDK 8/11/17/21 the build matrix must pass:
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
   - Runs `clean deploy -Prelease` — this builds all modules, attaches
     `*-sources.jar` + `*-javadoc.jar`, signs them with GPG, and uploads them to the
     Central Publishing Portal into a **staging** deployment.
   - Packages the agent jar and creates the GitHub Release with the jar + SHA-256.
6. **Publish from staging.** Log into <https://central.sonatype.com>, find the new
   deployment, and click **Publish**. Until you do this the artifacts are not visible
   on Maven Central. (Auto-publish can be configured on the Portal if you prefer.)
7. Verify on Central (may take ~15–30 min to propagate after publishing):
   `https://central.sonatype.com/artifact/com.github.cc11001100/weaver-girl-core`

## Notes

- The compiler targets Java 1.8 bytecode regardless of the JDK building the release;
  the workflow uses JDK 17 only because the release tooling and GPG signing run best
  on it.
- `quality-gate` is disabled during `deploy` (it runs the full test suite, just
  without re-enforcing the coverage floor — that is already enforced on every PR).
- If the Central deploy fails, the GitHub Release is **not** created (the agent-jar
  verification + release steps come after deploy). Fix the issue, delete the tag
  locally and remotely, re-tag, and push again.
