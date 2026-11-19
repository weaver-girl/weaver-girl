FROM eclipse-temurin:8-jre

# The version changes on every release; pass it when building the image, e.g.
#   docker build --build-arg AGENT_VERSION=1.0.0 -t weaver-girl .
ARG AGENT_VERSION=1.0.0-SNAPSHOT

# Copy the agent JAR and the plugins/ directory it discovers capabilities from.
# The agent jar has no built-in plugin classes; without plugins/ it instruments nothing.
COPY weaver-girl-agent/target/weaver-girl-agent-${AGENT_VERSION}.jar /opt/weaver-girl/weaver-girl-agent.jar
COPY weaver-girl-agent/target/plugins /opt/weaver-girl/plugins

# The image is a base for application images: the only thing a consumer adds is ${APP_JAR}.
#
# The endpoints are on by default — a container whose agent exposes nothing cannot be probed or
# scraped. They bind 0.0.0.0 because a loopback-bound endpoint inside a container is invisible
# even to the node's kubelet, so the image opts in explicitly; the ports still need publishing
# (the Helm chart does it) and should sit behind a network policy, since the endpoints have no
# authentication unless endpointAuthToken is set.
ENV JAVA_AGENT_OPTS="-javaagent:/opt/weaver-girl/weaver-girl-agent.jar=metricsPort=9400,healthPort=9401,diagnosticsPort=9402,endpointBindAddress=0.0.0.0"

# APP_JAR stays empty on purpose: an application image sets it, and the entry point refuses to
# start without it instead of failing with "Unable to access jarfile".
ENV APP_JAR=""
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD sh -c 'exec 3<>/dev/tcp/127.0.0.1/9401 && printf "GET /ready HTTP/1.0\r\n\r\n" >&3 && grep -q "200 OK" <&3' || exit 1

RUN addgroup --system weaver 2>/dev/null || addgroup weaver; \
    adduser --system --ingroup weaver weaver 2>/dev/null || adduser -S -G weaver weaver
USER weaver

# Entry point: run any Java app with the agent attached. APP_JAR must be set by the application
# image; the error is explicit instead of "Unable to access jarfile".
ENTRYPOINT ["sh", "-c", "test -n \"$APP_JAR\" || { echo 'APP_JAR is not set: build FROM this image and set ENV APP_JAR=/path/to/app.jar' >&2; exit 1; }; exec java $JAVA_AGENT_OPTS -jar \"$APP_JAR\""]
