FROM gradle:8.14.3-jdk21

ARG PAPER_TRAIL_APPLICATION_REVISION=unversioned
ARG PAPER_TRAIL_EVALUATOR_UID=1000
ARG PAPER_TRAIL_EVALUATOR_GID=1000
ENV PAPER_TRAIL_APPLICATION_REVISION=${PAPER_TRAIL_APPLICATION_REVISION}
ENV HOME=/workspace
ENV GRADLE_USER_HOME=/workspace/.gradle

WORKDIR /workspace
COPY api/ ./api/
COPY docs/benchmarks/v1-calibration-fixture.json ./docs/benchmarks/v1-calibration-fixture.json

# Resolve the exact evaluator runtime classpath during image build. The running evaluator
# can then stay on the internal, no-egress Laya network and execute Gradle in offline mode.
RUN cd api && ./gradlew --no-daemon calibrate
RUN chown -R "${PAPER_TRAIL_EVALUATOR_UID}:${PAPER_TRAIL_EVALUATOR_GID}" /workspace
USER ${PAPER_TRAIL_EVALUATOR_UID}:${PAPER_TRAIL_EVALUATOR_GID}

WORKDIR /workspace/api
ENTRYPOINT ["./gradlew", "--offline", "evaluateLaya"]
