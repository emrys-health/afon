# linux/amd64 or linux/arm64: TARGETARCH is amd64 or arm64, mapped to each download's own arch name where it differs
# The tools that are static binaries are copied from their official images, rather than downloaded from the release pages of GitHub,
# which answer with gateway errors at times.  They are the tags of the images: the versions without the leading v.  The image of eksctl
# is for amd64 only, so that tool is not usable in an arm64 image.  OpenTofu has no image on Docker Hub: its own, from ghcr.io, is the
# -minimal one, as the default one cannot be a build stage.
ARG JAVA_VERSION=17
ARG TOFU_VERSION=1.13.2
ARG EKSCTL_VERSION=v0.215.0
ARG YQ_VERSION=4.54.1
ARG KUBELOGIN_VERSION=v0.2.22
ARG EKSCTL_PLATFORM=linux/amd64

FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS jdk
FROM ghcr.io/opentofu/opentofu:${TOFU_VERSION}-minimal AS tofu
FROM --platform=${EKSCTL_PLATFORM} weaveworks/eksctl:${EKSCTL_VERSION} AS eksctl
FROM mikefarah/yq:${YQ_VERSION} AS yq
FROM ghcr.io/azure/kubelogin:${KUBELOGIN_VERSION} AS kubelogin

FROM ubuntu:22.04

ARG TARGETARCH

# The options of every download: fail on an HTTP error, rather than save its body as the file, and retry the transient failures
ARG CURL_OPTS="-fsSL --retry 5 --retry-delay 10 --retry-all-errors"

ARG AWS_CLI_VERSION=2.30.1
ARG NODE_VERSION=22.23.4
ARG AZ_CLI_VERSION=2.90.0
ARG GCLOUD_VERSION=565.0.0
ARG HELM_VERSION=v4.3.1
ARG KUBECTL_VERSION=v1.37.2

ARG MAVEN_VERSION=3.9.16

ENV JAVA_HOME=/opt/java
ENV MAVEN_HOME=/opt/maven
ENV NODE_HOME=/opt/node
ENV GCLOUD_HOME=/opt/google-cloud-sdk
ENV PATH=$JAVA_HOME/bin:$MAVEN_HOME/bin:$NODE_HOME/bin:$GCLOUD_HOME/bin:$PATH

RUN apt-get update && apt-get install -y \
    curl \
    less \
    zip \
    unzip \
    jq \
    dnsutils \
    ca-certificates \
    gnupg \
    python-is-python3 \
    git \
    git-lfs \
    libicu70 \
    && rm -rf /var/lib/apt/lists/*

# The JDK is the Temurin one of the official image, which is the Adoptium build, rather than a download from the Adoptium API,
# which answers with gateway errors at times.  The image has a variant for each architecture.
COPY --from=jdk /opt/java/openjdk $JAVA_HOME

RUN curl $CURL_OPTS https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    -o maven.tar.gz \
 && mkdir -p $MAVEN_HOME \
 && tar -xzf maven.tar.gz -C $MAVEN_HOME --strip-components=1 \
 && rm maven.tar.gz

COPY --from=tofu /usr/local/bin/tofu /usr/local/bin/tofu

RUN ARCH=$([ "$TARGETARCH" = arm64 ] && echo aarch64 || echo x86_64) \
 && curl $CURL_OPTS "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}-${AWS_CLI_VERSION}.zip" -o "awscliv2.zip" \
 && unzip -q awscliv2.zip \
 && ./aws/install \
 && rm -rf aws awscliv2.zip

COPY --from=eksctl /eksctl /usr/local/bin/eksctl

RUN curl $CURL_OPTS "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" \
    | tar -xz -C /usr/local/bin --strip-components=1 linux-${TARGETARCH}/helm

COPY --from=yq /usr/bin/yq /usr/local/bin/yq

RUN ARCH=$([ "$TARGETARCH" = arm64 ] && echo arm64 || echo x64) \
 && curl $CURL_OPTS https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${ARCH}.tar.gz -o node.tar.gz \
 && mkdir -p /opt/node \
 && tar -xzf node.tar.gz -C /opt/node --strip-components=1 \
 && rm node.tar.gz

RUN ARCH=$([ "$TARGETARCH" = arm64 ] && echo arm || echo x86_64) \
 && curl $CURL_OPTS https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GCLOUD_VERSION}-linux-${ARCH}.tar.gz -o gcloud.tar.gz \
 && tar -xzf gcloud.tar.gz -C /opt \
 && /opt/google-cloud-sdk/install.sh --quiet --usage-reporting=false --path-update=false \
 && rm gcloud.tar.gz \
 # the plugin the Kubernetes and Helm providers run to get a token for GKE, which the tarball does not include
 && /opt/google-cloud-sdk/bin/gcloud components install gke-gcloud-auth-plugin --quiet \
 && /opt/google-cloud-sdk/bin/gke-gcloud-auth-plugin --version

# Add Microsoft signing key + repo and install exact version
RUN curl $CURL_OPTS https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor \
    | install -o root -g root -m 644 /dev/stdin /usr/share/keyrings/microsoft.gpg \
 && echo "deb [arch=${TARGETARCH} signed-by=/usr/share/keyrings/microsoft.gpg] https://packages.microsoft.com/repos/azure-cli/ jammy main" \
    > /etc/apt/sources.list.d/azure-cli.list \
 && apt-get update \
 && apt-get install -y azure-cli=${AZ_CLI_VERSION}-1~jammy \
 && rm -rf /var/lib/apt/lists/*

# kubectl, from the Kubernetes release server, and kubelogin from its image, instead of 'az aks install-cli', which downloads the latest
# of both and kubelogin from the release pages of GitHub
RUN curl $CURL_OPTS https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl -o /usr/local/bin/kubectl \
 && chmod 0755 /usr/local/bin/kubectl
COPY --from=kubelogin /usr/local/bin/kubelogin /usr/local/bin/kubelogin

ARG AFON_VERSION
ARG AFON_COMMIT
ARG AFON_REPO
ARG AFON_CREATED
LABEL org.opencontainers.image.title="afon-base" \
      org.opencontainers.image.version="${AFON_VERSION}" \
      org.opencontainers.image.revision="${AFON_COMMIT}" \
      org.opencontainers.image.source="${AFON_REPO}" \
      org.opencontainers.image.created="${AFON_CREATED}" \
      org.opencontainers.image.description="Afon base image"

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["bash"]
