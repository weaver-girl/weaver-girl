FROM eclipse-temurin:8-jre

# The version changes on every release; pass it when building the image, e.g.
#   docker build --build-arg AGENT_VERSION=1.0.0 -t weaver-girl .
ARG AGENT_VERSION=1.0.0-SNAPSHOT

# Copy the agent JAR and the plugins/ directory it discovers capabilities from.
# The agent jar has no built-in plugin classes; without plugins/ it instruments nothing.
COPY weaver-girl-agent/target/weaver-girl-agent-${AGENT_VERSION}.jar /opt/weaver-girl/weaver-girl-agent.jar
COPY weaver-girl-agent/target/plugins /opt/weaver-girl/plugins
COPY weaver-example.yml /opt/weaver-girl/weaver-example.yml

# Default: attach agent with YAML config
ENV JAVA_AGENT_OPTS="-javaagent:/opt/weaver-girl/weaver-girl-agent.jar=config=/opt/weaver-girl/weaver-example.yml"

# Entry point: run any Java app with the agent attached
ENTRYPOINT ["sh", "-c", "java $JAVA_AGENT_OPTS -jar $APP_JAR"]
