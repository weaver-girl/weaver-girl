#!/bin/bash
# Run the weaver-girl sample application with the Java agent attached.
#
# The sample app needs Jetty/H2/Servlet on its runtime classpath, so this script copies the
# module's dependencies next to the classes first. The agent jar is picked from the main jar
# only — the -javadoc and -sources jars in the same directory have no Premain-Class.
#
# The sample binds 8080 by default; if that port is taken, pass PORT:  PORT=18099 ./run-sample.sh
set -e
cd "$(dirname "$0")"

PORT=${PORT:-8080}

echo "Building weaver-girl..."
./mvnw -B package -DskipTests -q

AGENT_JAR=$(find weaver-girl-agent/target -maxdepth 1 -name "weaver-girl-agent-*.jar" \
    ! -name "*sources*" ! -name "*javadoc*" | head -1)
if [ -z "$AGENT_JAR" ]; then
    echo "ERROR: Agent JAR not found. Build failed?"
    exit 1
fi

# The agent discovers built-in capability jars from this directory. The reactor build leaves
# them in weaver-girl-agent/target/plugins.
PLUGIN_DIR="weaver-girl-agent/target/plugins"
if [ ! -d "$PLUGIN_DIR" ]; then
    echo "ERROR: $PLUGIN_DIR not found; the build did not produce the capability jars."
    exit 1
fi

echo "Resolving the sample's runtime dependencies..."
./mvnw -B -q -pl weaver-girl-sample dependency:copy-dependencies -DoutputDirectory=target/dependency

echo "Running sample with agent: $AGENT_JAR (port $PORT)"
exec java \
    -javaagent:"$AGENT_JAR"=plugins="$PLUGIN_DIR" \
    -Dsample.port="$PORT" \
    -cp "weaver-girl-sample/target/classes:weaver-girl-sample/target/dependency/*" \
    com.github.cc11001100.weavergirl.sample.app.SampleApplication
