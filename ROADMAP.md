# Roadmap

Weaver-Girl is a **hook base**, not an APM product. It owns interception, plugin
isolation, and the shared runtime services (tracing, metrics, export, topology,
alerting, taint). An APM agent, an IAST scanner, a RASP shield, an audit trail or
a resilience layer is a plugin built on that base — see
[`docs/ant-aspect-base-decision.md`](docs/ant-aspect-base-decision.md).

This roadmap covers what the project itself will add. It does not promise dates;
items move when a release is cut and when contributors pick them up. Discuss a
proposal in [Discussions](https://github.com/weaver-girl/weaver-girl/discussions)
before opening an issue for anything large.

## 1.0 — public launch

The code and packaging are ready; see [`RELEASE_READINESS.md`](RELEASE_READINESS.md).
What remains is launch hygiene, not engineering:

- [x] Maven Central metadata points at the organization, not a personal fork
- [x] Release notes link to the correct repository and use the released version
- [x] Plugin template repository, marked as a GitHub template
- [x] Code of Conduct, security policy, issue forms and PR template
- [x] Versioning and compatibility policy published, coordinates moved to `io.github.cc11001100`
- [x] Website on the organization root (`https://weaver-girl.github.io/`)
- [x] Repository description, homepage, topics and private vulnerability reporting configured
- [x] Discussions enabled
- [ ] Central namespace `io.github.cc11001100` verified on central.sonatype.com
- [ ] Central Publishing credentials and GPG key configured as repository secrets
- [ ] `v1.0.0` tag pushed and the staging deployment published
- [ ] Seed “good first issue” issues and apply labels
- [ ] Launch post, demo recording and a short “what it is / is not” explainer
- [ ] `weaver-girl-examples` repository created from released artifacts (after 1.0.0)

## 1.x — ecosystem and delivery

Goal: a third-party developer can go from zero to a working plugin without
reading the engine source, and an operator can deploy the agent without building
it.

### Developer experience

- **Examples repository.** One folder per scenario — servlet + JDBC, Dubbo,
  Spring Boot, Kafka, IAST taint flow, RASP block — each depending on released
  artifacts, not the reactor. The in-tree `weaver-girl-sample` stays as an
  integration test.
- **Compatibility matrix.** Generate a page from plugin metadata plus a smoke
  job per framework, so “does it support Spring Boot 3.3?” has a tested answer.
- **Versioned documentation site.** Publish the `docs/` tree with version
  switching; today the marketing site links into GitHub, which is fine for 1.0
  but does not scale.
- **Plugin catalogue.** A curated list of third-party plugins, the template
  repository, and the contract they build against.
- **Benchmark report.** Publish methodology and comparative overhead with the
  same caveats as `docs/benchmarks/`; do not compare against untuned defaults.

### Plugin coverage

The long tail is demand-driven. Known gaps worth tracking:

- Spring Cloud Gateway / Zuul 2
- Armeria, Dubbo 3, SOFA RPC, Motan
- Kafka Streams (beyond the consumer/producer hooks)
- MongoDB async driver
- Log4j2 / Logback MDC correlation (the logging plugin covers the event path,
  not log-line injection)
- SQL normalization and slow-query attribution across the JDBC family

### Operations

- **Container image** published to GHCR on every release, with the `plugins/`
  directory embedded and documented as a base image for application images.
- **Helm chart** published as an OCI artifact; values verified against the
  released image tag.
- **Dashboard packs** for Grafana, and an OpenTelemetry Collector example that
  matches the exported metric/span names.
- **Management endpoint hardening**: explicit bind address, optional bearer
  token, TLS termination guidance. Today operators must isolate the ports.
- **Curated plugin bundles** (`web`, `data`, `messaging`, `security`) so a
  production deployment does not load every capability by default.

### Supply chain

- SBOM (CycloneDX) attached to each release.
- Build provenance attestation and cosign signatures for the distribution
  archive, alongside the existing SHA-256 files.
- Reproducible-build verification in CI.

## 2.0 — platform depth

Goal: the base can carry products that today need their own runtime.

- **Continuous profiling** as a runtime capability (CPU, allocation, lock),
  exposed through the same plugin lifecycle and export path.
- **IAST / RASP reference products.** A rule DSL, a source/propagator/sink
  registry, a findings model and two reference applications. The taint runtime
  already provides the data flow; what is missing is the product layer that
  proves the base can carry it.
- **Management console.** A read-only view of plugins, hooks, health, topology
  and config, shipped separately from the agent. The base should not grow a UI.
- **OpenTelemetry semantic conventions** alignment for span and metric names,
  with a compatibility switch for existing dashboards.
- **Opt-in remote control plane** for fleet-wide config and plugin rollout,
  explicitly off by default and authenticated.
- **Native-image story.** Document what instrumentation can and cannot do under
  GraalVM native images instead of implying parity with JVM mode.

## Explicitly out of scope

- An APM backend: storage, query language, long-term retention, hosted SaaS.
- A bundled security rule pack or a certified scanner. The base exposes taint
  and interception; rules belong to the product.
- Replacing the OpenTelemetry SDK. Weaver-Girl exports to OTel; it does not
  reimplement the API.
- A plugin marketplace with billing or review workflow.

## How to influence the roadmap

- Open a [Discussion](https://github.com/weaver-girl/weaver-girl/discussions) for
  a design question.
- Open a feature request with the scope, the use case and the proposed API.
- Start a third-party plugin from
  [`weaver-girl-plugin-template`](https://github.com/weaver-girl/weaver-girl-plugin-template);
  plugins that prove useful can be linked from the catalogue without entering
  the core reactor.
