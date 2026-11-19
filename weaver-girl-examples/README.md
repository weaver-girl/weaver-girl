# Weaver-Girl examples

Standalone example projects that depend on **released** Weaver-Girl artifacts and the released agent
distribution. Nothing here is a module of the reactor: no example has a `<parent>` pointing at
`weaver-girl`, and each builds with its own Maven invocation.

That is the point. `weaver-girl-sample` inside the reactor proves the engine works against the code
you just changed; these examples prove a new user can get there from the published artifacts alone,
which is a different and much easier thing to get wrong.

| Example | Shows | Depends on |
|---|---|---|
| [`spring-boot-jdbc`](spring-boot-jdbc/) | A Spring Boot 3.3 + H2 application, attached with `-javaagent`, serving REST endpoints over JDBC. Servlet, Spring MVC and JDBC interception, plus the agent's `/health`, `/ready`, `/metrics` and `/stats` endpoints. | Spring Boot 3.3.5, H2. **No** weaver-girl artifact at compile time. |

## `spring-boot-jdbc`

```bash
cd spring-boot-jdbc
AGENT_ZIP=/path/to/weaver-girl-agent-1.0.0-dist.zip ./run.sh
```

`run.sh` builds the app, unpacks the agent distribution, attaches it, and prints the endpoints. Then:

```bash
curl -s localhost:8080/orders
curl -s localhost:8080/orders/slow
curl -s localhost:8081/metrics | grep 'plugin="servlet"'
```

The last line is the real check. A `weavergirl_operation_duration_ms_count{plugin="servlet"}` above
zero means the request was actually intercepted — the agent did not merely start.

### Why the distribution zip, not the jar

The agent discovers its 126 capability jars from the `plugins/` directory that sits next to the agent
jar. Attaching a bare `weaver-girl-agent-<version>.jar` starts the agent and instruments nothing.
`run.sh` fails loudly if the archive does not contain both.

### What this example proved, and what it fixed

Running it end to end found three defects that only a real framework deployment surfaces, all fixed
in the agent:

1. **Spring Boot fat jars were invisible to plugin activation.** In a `java -jar app.jar` layout the
   application and its framework jars live inside the launcher jar, reachable only through Spring
   Boot's own loader — which does not exist yet in `premain`. Every plugin that checks "is my
   library present" answered no, so servlet, Spring MVC and Tomcat deactivated themselves in the
   most common deployment shape in the ecosystem. `PluginContext.classPresent` now falls back to an
   index of the launch class path that understands `BOOT-INF/classes/` and `BOOT-INF/lib/*.jar`.
2. **The `servlet` plugin only declared the `javax` namespace**, so a Spring Boot 3 (`jakarta`)
   application deactivated it even when the class check worked.
3. **Two classpath collisions from the agent jar.** `-javaagent` puts the agent jar on the
   application class path, so a host that bundles the same library can resolve the agent's copy.
   Multi-release entries for relocated packages kept their original names and broke snakeyaml on
   Spring Boot's YAML path; micrometer was bundled unrelocated and broke actuator's logback metrics.
   Both are fixed in the agent build (`scripts/relocate-multi-release.py`, an `artifactSet` exclude).
