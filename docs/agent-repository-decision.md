# Agent repository decision

Shallow clones read on 2026-09-29 (not vendored into this tree):

- Apache SkyWalking Java agent, `apache/skywalking-java` at `ea2fb09b0736cbbcc28b66ca57ca6513f6c9284f`.
  `ProfileTaskChannelService` pulls profiling tasks from the backend over gRPC and the agent executes them. This repository does not accept remote profiling tasks.
- Elastic APM Java agent, `elastic/apm-agent-java` at `883b3e148de18708103632cf3d9392fb3fdfe2fa`.
  `ApmServerConfigurationSource` polls the APM Server for central configuration and applies it without a restart. This repository's dynamic config is local; it does not poll a remote control plane.

`gh repo list weaver-girl` shows three repositories:

- `weaver-girl` — this agent
- `weaver-girl-website-frontend`
- `weaver-girl-website-backend`

The agent Maven modules (`weaver-girl-api`, `weaver-girl-core`, `weaver-girl-plugins`, `weaver-girl-agent`, and the annotation modules) stay in this repository. They share one reactor, one shaded agent jar, and one advice-bridge class loader. Splitting them into git submodules would make a single clone unable to build that jar without extra remotes. The two website repositories stay separate; they are not submodules of this repo.
