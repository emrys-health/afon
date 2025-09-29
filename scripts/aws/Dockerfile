# Dockerfile
FROM ubuntu:22.04 AS donor
FROM apache/spark:3.5.0

COPY --from=donor /usr/bin/apt-get ./apt-get
COPY --from=donor /usr/bin/apt ./apt
COPY --from=donor /usr/lib/apt ./usr-lib-apt
COPY --from=donor /etc/apt ./etc-apt
COPY --from=donor /usr/lib/x86_64-linux-gnu/libapt* ./usr-lib-apt-libs

USER root

# Install Python 3.11
RUN apt-get update && \
    apt-get install -y software-properties-common && \
    add-apt-repository ppa:deadsnakes/ppa && \
    apt-get update && \
    apt-get install -y python3.11 python3.11-distutils python3.11-venv curl && \
    curl -sS https://bootstrap.pypa.io/get-pip.py | python3.11 && \
    update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.11 2 && \
    python3 --version

USER spark

# Ensure pyspark finds the right python
ENV PYSPARK_PYTHON=python3
ENV PYSPARK_DRIVER_PYTHON=python3