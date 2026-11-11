# ---- Build: compile and package a single runnable JAR -------------------------------------------
FROM eclipse-temurin:21-jdk AS build

ARG SBT_VERSION=1.10.2
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      | tar xz -C /opt
ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /app

# Resolve dependencies in their own layer so code changes don't re-download them.
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
RUN sbt -batch update

COPY src src
RUN sbt -batch assembly

# ---- Run: a slim JRE image running as a non-root user ------------------------------------------
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/target/scala-3.3.4/todo-backend.jar app.jar
USER app

# A fixed, small heap. Sizing it as a share of the container's memory would let the JVM grow to
# fit whatever limit the host allows, and on usage-billed hosts that RAM is what you pay for.
# The serial collector suits a small single-instance app; thread stacks are halved as well.
# `java` reads JDK_JAVA_OPTIONS itself, so override it per environment without a rebuild.
ENV JDK_JAVA_OPTIONS="-Xmx256m -XX:+UseSerialGC -Xss512k"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
