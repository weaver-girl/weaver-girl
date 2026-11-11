# Versioning and compatibility

This document is the promise Weaver-Girl makes to users and plugin authors.
It applies from `1.0.0` onward.

## Semantic versioning

Releases follow [Semantic Versioning 2.0.0](https://semver.org/):

| Change | Version |
| --- | --- |
| Incompatible change to the public API, plugin contract, or configuration keys | **MAJOR** |
| New capability, new plugin, new extension point, deprecation | **MINOR** |
| Bug fix, performance fix, documentation, new instrumentation for an existing plugin | **PATCH** |

The Maven version is the tag without the `v` prefix: tag `v1.2.3` publishes
`1.2.3`. Pre-releases use `1.2.3-rc1`.

## What is public

The following are covered by the compatibility promise:

- **`weaver-girl-api`** — the interfaces, value objects and extension points a
  plugin compiles against. Every public type carries `@since`.
- **`weaver-girl-annotation` / `weaver-girl-annotation-runtime`** — the
  annotation-driven interception surface.
- **The plugin metadata contract** — `META-INF/services/...WeaverPlugin`,
  `META-INF/weaver-girl-plugin.properties` (`plugin.name`, `plugin.version`,
  `plugin.apiVersion`, `plugin.minimumAgentVersion`, `plugin.requires`,
  `plugin.exports`), and the `plugins/` directory layout.
- **Published configuration keys** in
  [`yaml-config-reference.md`](yaml-config-reference.md) and the documented
  agent options.
- **The HTTP endpoints** documented for health, metrics, management and
  diagnostics, including their response shapes.

Everything else is internal and may change in a MINOR release: `weaver-girl-core`
implementation classes, the transformer, the registry implementation, the
shaded ByteBuddy relocation, and the built-in plugin implementations. A product
must not compile against `weaver-girl-core`.

## Plugin API version

`WeaverPlugin.API_VERSION` is the plugin contract version. A plugin declares the
contract it was built against:

```
META-INF/weaver-girl-plugin.properties
    plugin.apiVersion=1.0.0
    plugin.minimumAgentVersion=1.0.0
```

The loader refuses a jar whose `apiVersion` is newer than the running agent, and
a jar whose `minimumAgentVersion` is not met. Jars that declare neither are
loaded unchecked, which is how the built-in jars ship.

Rule of thumb:

- A plugin built against `1.x` runs on any later `1.y` agent.
- An agent `1.y` may load plugins built against any earlier `1.x`.
- A MAJOR release may change the contract; the new `API_VERSION` makes old jars
  fail closed instead of halfway through `init`.

## Java support

| | Supported |
| --- | --- |
| Agent runtime | Java 8, 11, 17, 21 (packaged-agent smoke on each) |
| Build JDK | 17 (compiler targets Java 8 bytecode with `--release 8`) |
| Test sources | JDK 17+ (JUnit 6) |

Main sources must stay Java 8 compatible: no `var`, no `Set.of()`, no
`ProcessHandle`, no text blocks, no switch expressions. A build on a newer JDK is
not evidence that the artifact runs on 8 — the packaged-agent smoke is.

## Framework and library support

Support is per instrumentation plugin. A plugin declares the libraries it
instruments, and is inactive when they are absent. The
[plugin list](../README.md#features) names the supported families; version-level
compatibility is validated by each plugin's tests and the agent smoke, not by an
exhaustive matrix yet. The compatibility matrix is a 1.x roadmap item.

Instrumentation is **best effort by design**: a framework upgrade may change a
method signature and silently stop matching. Treat a missing interception as a
bug report, and pin the framework version in production until the matrix covers
it.

## Configuration compatibility

- Adding a key is a MINOR change.
- Renaming or removing a key is a MAJOR change, with a deprecation alias kept for
  at least one MINOR release where practical (an example is the `otlpEndpoint`
  deprecated alias).
- Unknown keys are reported by config validation; they are not silently ignored.

## Deprecation

A public API or configuration key is deprecated with:

1. a `@Deprecated` annotation and Javadoc naming the replacement,
2. a note in the release notes and `CHANGELOG.md`,
3. removal no earlier than the next MAJOR release.

## Release cadence

- **Patch** releases as needed for correctness and security.
- **Minor** releases roughly quarterly, when a coherent set of plugins or
  capabilities is ready.
- **Major** releases when the contract must change; no fixed schedule.

Security fixes may be backported to the previous minor, as described in
[`SECURITY.md`](../SECURITY.md).

## Upgrade guidance

- Read the release notes and `CHANGELOG.md` before a MINOR upgrade.
- Keep the `plugins/` directory and the agent jar from the same release; mixing
  versions is unsupported.
- For a MAJOR upgrade, rebuild third-party plugins against the new
  `weaver-girl-api` and declare the new `plugin.apiVersion`.
- Verify the release archive checksum before rollout.

## Maven coordinates

```
io.github.cc11001100:weaver-girl-api
io.github.cc11001100:weaver-girl-annotation
io.github.cc11001100:weaver-girl-core
io.github.cc11001100:weaver-girl-testkit
io.github.cc11001100:weaver-girl-<plugin|runtime|bridge|agent>
```

The group id is the `io.github.<user>` namespace that Maven Central provisions
automatically for a GitHub sign-in, and is frozen at `1.0.0`; changing it after a
public release would be a breaking change for every consumer. Java packages keep
the historical `com.github.cc11001100.weavergirl` prefix: a group id and a Java
package are independent namespaces. Central does not auto-provision organization
namespaces (`io.github.weaver-girl`), so the project namespace is tied to the
maintainer's GitHub account even though the repository lives in the organization.
