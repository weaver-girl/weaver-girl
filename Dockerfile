FROM eclipse-temurin:8-jre

# Copy the agent JAR and config
COPY weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar /opt/weaver-girl/weaver-girl-agent.jar
COPY weaver-example.yml /opt/weaver-girl/weaver-example.yml

# Default: attach agent with YAML config
ENV JAVA_AGENT_OPTS="-javaagent:/opt/weaver-girl/weaver-girl-agent.jar=config=/opt/weaver-girl/weaver-example.yml"

# Entry point: run any Java app with the agent attached
ENTRYPOINT ["sh", "-c", "java $JAVA_AGENT_OPTS -jar $APP_JAR"]