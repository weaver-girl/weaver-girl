# Positioning: what Weaver-Girl is, and what it is not

Weaver-Girl is a **hook base** for JVM products. It gives a team the hard part
of a runtime-instrumentation product — bytecode interception, plugin isolation,
lifecycle, configuration, tracing, metrics, export, topology, alerting and taint
— and leaves the product decisions to a plugin.

The same base can carry several products without forking the engine:

| Product | What the plugin adds | What the base already provides |
| --- | --- | --- |
| APM | Span naming, service/resource conventions, backend exporter | Interception, context propagation, spans, metrics, OTLP/Prometheus |
| IAST | Source/sink rules, findings, reporting policy | Taint data flow, method/field/catch interception, event export |
| RASP | A rule set and a block/allow decision | Around advice (skip, rewrite return, replace exception), isolation, circuit breaker |
| Audit trail | Which calls matter and where to store them | Method and field interception, structured events, async export |
| Resilience | Failure policy for a specific downstream | Interceptor registry, circuit breaker, sampling, hot reload |

## How it compares

| | Weaver-Girl | Apache SkyWalking | OpenTelemetry Java agent | Elastic APM Java agent |
| --- | --- | --- | --- | --- |
| Primary scope | Hook base for building products | Full APM platform | Standard instrumentation + SDK | APM agent for Elastic |
| Backend / UI | None | Included | None (bring your own) | Elastic Stack |
| Extension model | Product plugin against a small API, isolated class loader | Agent plugins | Instrumentation modules + SDK | Agent plugins |
| Build-your-own product | First-class goal | Possible but coupled to the platform | SDK-centric | Not the goal |
| Export | OTLP, Prometheus, JSON events, custom SPI | SkyWalking protocol, OTel | OTLP and ecosystem exporters | Elastic protocol |
| License | MIT | Apache-2.0 | Apache-2.0 | Apache-2.0 (agent) |

The comparison is about scope, not quality. SkyWalking is the right choice if
you want a complete APM platform today. The OpenTelemetry agent is the right
choice if you want the standard SDK and ecosystem. Weaver-Girl is for the team
that needs *its own* product on top of a stable hook layer — an internal APM
with company-specific conventions, an IAST/RASP capability, a compliance audit
trail — and does not want to fork a full platform to get it.

## Choose Weaver-Girl if

- You are building a runtime-instrumentation product, not buying one.
- You need method, field and catch interception with per-plugin isolation, and
  you need a failing plugin to be contained rather than crash the JVM.
- You want OTLP/Prometheus export without adopting the OpenTelemetry SDK as your
  programming model.
- You need one runtime to carry several products (APM + security + audit) with a
  shared context and configuration model.
- You want to ship third-party plugins without giving them the engine's internals
  — the contract is `weaver-girl-api`, and each plugin gets its own class loader.

## Do not choose Weaver-Girl if

- You want a turnkey APM UI, storage and query language. Use SkyWalking, or
  OpenTelemetry plus a backend.
- You need a certified security scanner with a maintained rule pack. The base
  gives you taint and interception; the rules are a product you build.
- You need instrumentation for a language other than Java/Kotlin/Scala on the
  JVM.
- You cannot accept a Java agent in the process (for policy or native-image
  reasons). The hook base is an in-process agent by design.

## The two boundaries that matter

1. **Base vs product.** Anything that encodes a vendor's naming, rule set,
   retention or block policy belongs to a plugin. Anything every product needs —
   interception, context, isolation, config, export plumbing — belongs to the
   base. This boundary is why the core has no UI, no rule pack and no storage.
2. **Public API vs internals.** Products compile against `weaver-girl-api` only.
   The engine, the transformer and the shaded ByteBuddy are internal, so the
   base can evolve without breaking every plugin. See
   [`versioning-and-compatibility.md`](versioning-and-compatibility.md).

## Where to start

- Build a plugin: [`weaver-girl-plugin-template`](https://github.com/weaver-girl/weaver-girl-plugin-template)
- Understand the contract: [`plugin-developer-guide.md`](plugin-developer-guide.md)
- See the boundaries argued: [`ant-aspect-base-decision.md`](ant-aspect-base-decision.md)
- See what is planned: [`../ROADMAP.md`](../ROADMAP.md)
