ARG BASE_IMAGE=afon-cli
FROM ${BASE_IMAGE}

WORKDIR /azp/

COPY ./start.sh ./
RUN chmod +x ./start.sh

RUN useradd -m -d /home/agent agent
RUN chown -R agent:agent /azp /home/agent

USER agent

ARG BASE_IMAGE
ARG AFON_VERSION
ARG AFON_COMMIT
ARG AFON_REPO
ARG AFON_CREATED
LABEL org.opencontainers.image.title="afon-azure-agent" \
      org.opencontainers.image.version="${AFON_VERSION}" \
      org.opencontainers.image.revision="${AFON_COMMIT}" \
      org.opencontainers.image.source="${AFON_REPO}" \
      org.opencontainers.image.created="${AFON_CREATED}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}" \
      org.opencontainers.image.description="Afon Azure DevOps Pipelines agent image"

ENTRYPOINT [ "./start.sh" ]
