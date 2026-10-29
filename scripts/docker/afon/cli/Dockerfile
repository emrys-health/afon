# syntax=docker/dockerfile:1
ARG BASE_IMAGE=afon-base-image
FROM ${BASE_IMAGE} AS build

# == Build context ==
# We are going to build out the mvn project / CLI which can then be dumped into the built CLI
WORKDIR /src
COPY . .

# Idea behind this is the tests will fail out as we're not introducing configured context for the CLI build
#   This probably limits documentation generation - I'd imagine we could do this in a different step
RUN --mount=type=cache,target=/root/.m2 \
    java afon-ops/src/main/codegen/GenerateScriptMains.java afon-ops/target/generated-sources/scripts-main \
 && mvn -B -q -Pcli,-acceptance install -DskipTests -Dexec.skip=true -Dafon.cli.dir=/out/cli

# == Runtime ==
FROM ${BASE_IMAGE}

ENV AFON_CLI_DIR=/opt/afon/cli \
    PATH=/opt/afon/cli:${PATH} \
    SPRING_MAIN_BANNER_MODE=off

COPY --from=build --chmod=0755 /out/cli/afon-cli.sh /out/cli/afon-cli-scripts.sh ${AFON_CLI_DIR}/
COPY --from=build /out/cli/afon-cli.jar ${AFON_CLI_DIR}/
COPY --from=build /out/cli/scripts ${AFON_CLI_DIR}/scripts
COPY --from=build /out/cli/templates ${AFON_CLI_DIR}/templates
COPY --from=build /out/cli/docs ${AFON_CLI_DIR}/docs

RUN find ${AFON_CLI_DIR}/scripts -name '*.sh' -exec chmod 0755 {} + \
 && afon-cli.sh -c azure help | grep -q 'config-init' \
 && afon-cli.sh -c azure help | grep -q 'azure-issue-cert'

# Declared last, as they change every commit and would otherwise bust the cache of the steps above
ARG BASE_IMAGE AFON_VERSION AFON_COMMIT AFON_REPO AFON_CREATED
LABEL org.opencontainers.image.title="afon-cli" \
      org.opencontainers.image.version="${AFON_VERSION}" \
      org.opencontainers.image.revision="${AFON_COMMIT}" \
      org.opencontainers.image.source="${AFON_REPO}" \
      org.opencontainers.image.created="${AFON_CREATED}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}" \
      org.opencontainers.image.description="Afon command line image"
