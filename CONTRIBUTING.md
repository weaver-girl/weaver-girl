# Contributing to Weaver-Girl

Thank you for your interest in contributing to Weaver-Girl! This document provides guidelines for contributions.

## Code of Conduct

Be respectful, constructive, and inclusive. We follow the [Contributor Covenant](https://www.contributor-covenant.org/) Code of Conduct.

## How to Contribute

### Bug Reports

1. Check if the issue already exists in [GitHub Issues](https://github.com/cc11001100/weaver-girl/issues)
2. If not, open a new issue with:
   - Java version (`java -version`)
   - Weaver-Girl version
   - Steps to reproduce
   - Expected vs actual behavior
   - Agent log output (with `logLevel=DEBUG` if possible)

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Write code with tests
4. Ensure all tests pass: `mvn clean install`
5. Follow conventional commit format: `feat(scope): description`
6. Submit a PR with a clear description of the change

## Development Setup

```bash
# Prerequisites: Java 8+, Maven 3.6+
git clone https://github.com/cc11001100/weaver-girl.git
cd weaver-girl
mvn clean install
```

## Code Style

- Java 8 compatible (no `var`, no `Set.of()`, no `ProcessHandle`)
- 4-space indentation
- Javadoc on all public classes and methods
- No `System.out.println` in production code (use SLF4J)
- No empty catch blocks

## Project Structure

```
weaver-girl/
├── weaver-girl-api/         Plugin SDK (interfaces, matchers, context)
├── weaver-girl-core/        Core engine (transformer, registry, config)
├── weaver-girl-annotation/  Declarative annotations
├── weaver-girl-plugins/     Built-in instrumentation plugins
├── weaver-girl-agent/       Agent entry point (premain/agentmain)
└── weaver-girl-sample/      Sample application and plugins
```

## Writing a Plugin

See [Plugin Developer Guide](docs/plugin-developer-guide.md) for the complete tutorial.

Quick start:

1. Add dependency on `weaver-girl-api`
2. Extend `AbstractPlugin`
3. Override `name()` and `registerInterceptors()`
4. Register in `META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin`

## Testing

- Unit tests: JUnit 5 + Mockito
- Every new feature must have tests
- Run: `mvn test`
- Full build: `mvn clean install`

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
