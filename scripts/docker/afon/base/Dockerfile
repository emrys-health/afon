# amd64 only: the downloads below are all of the linux amd64/x64 binaries
FROM ubuntu:22.04

ARG TOFU_VERSION=1.13.2
ARG AWS_CLI_VERSION=2.30.1
ARG NODE_VERSION=22.23.4
ARG AZ_CLI_VERSION=2.90.0
ARG GCLOUD_VERSION=565.0.0
ARG EKSCTL_VERSION=v0.215.0
ARG HELM_VERSION=v4.3.1
ARG YQ_VERSION=v4.54.1

ARG JAVA_VERSION=17
ARG MAVEN_VERSION=3.9.16

ENV JAVA_HOME=/opt/java
ENV MAVEN_HOME=/opt/maven
ENV NODE_HOME=/opt/node
ENV GCLOUD_HOME=/opt/google-cloud-sdk
ENV PATH=$JAVA_HOME/bin:$MAVEN_HOME/bin:$NODE_HOME/bin:$GCLOUD_HOME/bin:$PATH

RUN apt-get update && apt-get install -y \
    curl \
    less \
    unzip \
    ca-certificates \
    gnupg \
    python-is-python3 \
 && rm -rf /var/lib/apt/lists/*

RUN curl -L https://api.adoptium.net/v3/binary/latest/${JAVA_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse \
    -o openjdk.tar.gz \
 && mkdir -p $JAVA_HOME \
 && tar -xzf openjdk.tar.gz -C $JAVA_HOME --strip-components=1 \
 && rm openjdk.tar.gz

RUN curl -L https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    -o maven.tar.gz \
 && mkdir -p $MAVEN_HOME \
 && tar -xzf maven.tar.gz -C $MAVEN_HOME --strip-components=1 \
 && rm maven.tar.gz

RUN curl -L https://github.com/opentofu/opentofu/releases/download/v${TOFU_VERSION}/tofu_${TOFU_VERSION}_linux_amd64.zip -o tofu.zip \
 && unzip -q tofu.zip tofu \
 && install -m 0755 tofu /usr/local/bin/tofu \
 && rm tofu tofu.zip

RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-${AWS_CLI_VERSION}.zip" -o "awscliv2.zip" \
 && unzip -q awscliv2.zip \
 && ./aws/install \
 && rm -rf aws awscliv2.zip

RUN curl -L "https://github.com/eksctl-io/eksctl/releases/download/${EKSCTL_VERSION}/eksctl_Linux_amd64.tar.gz" -o eksctl.tar.gz \
 && tar -xzf eksctl.tar.gz -C /usr/local/bin eksctl \
 && chmod +x /usr/local/bin/eksctl \
 && rm eksctl.tar.gz

RUN curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" \
    | tar -xz -C /usr/local/bin --strip-components=1 linux-amd64/helm \
 && curl -fsSLo /usr/local/bin/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64" \
 && chmod 0755 /usr/local/bin/yq

RUN curl -L https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz -o node.tar.gz \
 && mkdir -p /opt/node \
 && tar -xzf node.tar.gz -C /opt/node --strip-components=1 \
 && rm node.tar.gz

RUN curl -L https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GCLOUD_VERSION}-linux-x86_64.tar.gz -o gcloud.tar.gz \
 && tar -xzf gcloud.tar.gz -C /opt \
 && /opt/google-cloud-sdk/install.sh --quiet --usage-reporting=false --path-update=false \
 && rm gcloud.tar.gz

# Add Microsoft signing key + repo and install exact version
RUN curl -sL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor \
    | install -o root -g root -m 644 /dev/stdin /usr/share/keyrings/microsoft.gpg \
 && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/microsoft.gpg] https://packages.microsoft.com/repos/azure-cli/ jammy main" \
    > /etc/apt/sources.list.d/azure-cli.list \
 && apt-get update \
 && apt-get install -y azure-cli=${AZ_CLI_VERSION}-1~jammy \
 && rm -rf /var/lib/apt/lists/*

RUN az aks install-cli

ARG AFON_VERSION AFON_COMMIT AFON_REPO AFON_CREATED
LABEL org.opencontainers.image.title="afon-base-image" \
      org.opencontainers.image.version="${AFON_VERSION}" \
      org.opencontainers.image.revision="${AFON_COMMIT}" \
      org.opencontainers.image.source="${AFON_REPO}" \
      org.opencontainers.image.created="${AFON_CREATED}" \
      org.opencontainers.image.description="Afon base image"

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["bash"]
