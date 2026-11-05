# syntax=docker/dockerfile:1
# The runtime image of the afon-cli: the base image with the pre-built afon-cli zip (afon-distribution, 'cli' profile)
# installed.  The zip is built outside of this image, eg with maven in the afon-base image.  It is the build context, as afon-cli.zip.
ARG BASE_IMAGE=afon-base
FROM ${BASE_IMAGE}

ENV AFON_CLI_DIR=/opt/afon/cli \
    PATH=/opt/afon/cli:${PATH} \
    SPRING_MAIN_BANNER_MODE=off

RUN --mount=type=bind,source=afon-cli.zip,target=/tmp/afon-cli.zip \
    mkdir -p ${AFON_CLI_DIR} \
 && unzip -q /tmp/afon-cli.zip -d ${AFON_CLI_DIR} \
 && chmod 0755 ${AFON_CLI_DIR}/*.sh \
 && find ${AFON_CLI_DIR}/scripts -name '*.sh' -exec chmod 0755 {} +

# Declared last, as they change every commit and would otherwise bust the cache of the steps above
ARG BASE_IMAGE
ARG AFON_VERSION
ARG AFON_COMMIT
ARG AFON_REPO
ARG AFON_CREATED
LABEL org.opencontainers.image.title="afon-cli" \
      org.opencontainers.image.version="${AFON_VERSION}" \
      org.opencontainers.image.revision="${AFON_COMMIT}" \
      org.opencontainers.image.source="${AFON_REPO}" \
      org.opencontainers.image.created="${AFON_CREATED}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}" \
      org.opencontainers.image.description="Afon command line image"
