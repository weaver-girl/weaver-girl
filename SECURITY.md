# Security policy

Weaver-Girl attaches to production JVMs and inspects application traffic, so
security reports are taken seriously. Thank you for reporting responsibly.

## Supported versions

| Version | Supported |
| --- | --- |
| Latest `1.x` release | ✅ |
| Previous `1.x` minor | ✅ (security fixes) |
| Older releases | ❌ |

The `main` branch is the development line and is not a supported release.

## Reporting a vulnerability

**Do not open a public issue.** Report privately through GitHub:

<https://github.com/weaver-girl/weaver-girl/security/advisories/new>

Please include:

- affected version(s) and the JDK used,
- the attach mode (`-javaagent` at startup, or dynamic attach),
- a minimal reproduction — configuration, the instrumented framework and
  version, and the plugin(s) enabled,
- the impact you believe it has (data exposure, denial of service, code
  execution, privilege boundary, …),
- any suggested fix or mitigation.

If you cannot use GitHub advisories, contact the maintainer
[@cc11001100](https://github.com/cc11001100) to arrange an alternative channel.
Do not include secrets or production data in any report.

## Scope

In scope:

- the agent and its shaded ByteBuddy/class-loader boundary,
- built-in instrumentation plugins and runtime capability jars,
- the plugin loading, isolation and hot-reload path,
- the management/health/metrics HTTP endpoints,
- configuration parsing and the OTLP/JSON export paths.

Out of scope:

- vulnerabilities in a framework or library that a plugin instruments, unless
  Weaver-Girl's handling makes them worse,
- findings that require the attacker to already control the JVM, the
  configuration file, or the `plugins/` directory,
- reports produced only by a generic scanner without a reproducible impact.

## What to expect

- We aim to acknowledge a report within 5 working days.
- We will confirm the affected versions, prepare a fix and a regression test,
  and coordinate a disclosure date with you.
- A fix is released as a patch version, with an advisory crediting the reporter
  unless you ask to stay anonymous.
- Please give us a reasonable window (typically 90 days) before public
  disclosure.

## Hardening notes for operators

- Bind health, metrics and management endpoints to localhost or a private
  interface; they do not ship an authentication layer.
- Treat `weaver.yml` and the `plugins/` directory as privileged input: a plugin
  jar runs inside the application JVM.
- Prefer `enabledPlugins` over loading every capability in sensitive
  environments.
- Verify release checksums (`*.sha256`) and, for Maven artifacts, the published
  PGP signatures.
