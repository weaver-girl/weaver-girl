## Summary

<!-- What changes, and why. Link the issue this closes. -->

Closes #

## Type of change

- [ ] Bug fix
- [ ] New instrumentation plugin
- [ ] New runtime capability / extension point
- [ ] Agent behavior or configuration
- [ ] Documentation / tooling only

## How it was verified

<!-- Commands run, tests added, and the real-attach path exercised. -->

- [ ] `./mvnw -Pquality-gate clean verify` passes locally
- [ ] Added or updated tests for the change
- [ ] If the change affects packaging, verified the agent with `plugins/` next to it
- [ ] If the change affects a published artifact, checked source/javadoc jars still build

## Compatibility

- [ ] Main sources remain Java 8 compatible (no `var`, no `Set.of()`, no text blocks, …)
- [ ] No public API in `weaver-girl-api` was removed or changed incompatibly
- [ ] Plugin metadata (`plugin.name`, `plugin.apiVersion`) is unchanged, or the change is documented
- [ ] Documentation and `CHANGELOG.md` are updated

## Notes for reviewers

<!-- Anything that needs a second look: class-loader boundaries, hot reload, concurrency. -->
