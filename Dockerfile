# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl gnupg ca-certificates && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg && \
    apt-get update && \
    apt-get install -y --no-install-recommends sbt && \
    rm -rf /var/lib/apt/lists/*

COPY project/ project/
COPY build.sbt ./
RUN sbt -batch update

COPY src/ src/
RUN sbt -batch stage

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /src/target/universal/stage/ /app/
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["/app/bin/mcpswag"]
