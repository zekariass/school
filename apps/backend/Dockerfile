# syntax=docker/dockerfile:1
# Build context: apps/backend
#   docker build -t school-backend:local apps/backend

# ---- build: compile the jar and split it into Spring Boot layers ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# The pom selects JDK 25 through maven-toolchains-plugin; register this image's JDK.
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<toolchains>' \
    '  <toolchain>' \
    '    <type>jdk</type>' \
    '    <provides><version>25</version></provides>' \
    "    <configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration>" \
    '  </toolchain>' \
    '</toolchains>' > /root/.m2/toolchains.xml

# Resolve dependencies first so this layer is reused until the pom changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src src
RUN ./mvnw -B -ntp -o -DskipTests package \
    && java -Djarmode=tools -jar target/school-backend-*.jar extract \
        --layers --launcher --destination /workspace/layers

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/layers/dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/application/ ./

USER app
EXPOSE 8080
# Container-aware heap sizing; extra flags can still be added via JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
