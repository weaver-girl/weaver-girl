#!/bin/bash
# Run the weaver-girl sample application with the Java agent attached
set -e

echo "Building weaver-girl..."
mvn clean package -DskipTests -q

AGENT_JAR=$(find weaver-girl-agent/target -name "weaver-girl-agent-*.jar" | head -1)
if [ -z "$AGENT_JAR" ]; then
    echo "ERROR: Agent JAR not found. Build failed?"
    exit 1
fi

echo "Running sample with agent: $AGENT_JAR"
java \
    -javaagent:"$AGENT_JAR"=plugins=weaver-girl-sample/target \
    -cp "weaver-girl-sample/target/classes:weaver-girl-api/target/classes:weaver-girl-annotation/target/classes:weaver-girl-core/target/classes" \
    com.github.cc11001100.weavergirl.sample.app.SampleApplication
