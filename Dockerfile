# syntax=docker/dockerfile:1

# Builds a Kotlin/Native executable of the journey-engine samples and ships it
# in a minimal scratch image. The container prints the three realistic example
# journeys as serialized JSON.
#
# First build takes a while: Gradle downloads the Kotlin/Native toolchain.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY journey-engine ./journey-engine

RUN ./gradlew :journey-engine:linkReleaseExecutableLinuxX64 --no-daemon -q

FROM scratch
COPY --from=build /src/journey-engine/build/bin/linuxX64/releaseExecutable/journey-engine.kexe /journey-engine
ENTRYPOINT ["/journey-engine"]
