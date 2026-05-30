# syntax=docker/dockerfile:1.7
#
# Build context is target/universal/stage (produced by `sbt stage`).
# Build it outside Docker — running sbt inside Docker forces a fresh
# sbt install + dependency resolve + Scala 3 compile on every build,
# and arm64 emulation under QEMU multiplies that by 5–10×.

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY . /app/
# Pre-create mount-point directories so users can bind-mount without creating
# them manually. Spring Boot picks up /app/config/application.yml automatically.
RUN mkdir -p /app/config /app/specs
EXPOSE 8080
ENTRYPOINT ["/app/bin/mcpswag"]
