# Uses Spark 4
# Build it with:
# docker build -t ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1 .
# docker push ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1
#FROM quay.io/jupyter/base-notebook:lab-4.1.5
FROM jupyter/pyspark-notebook:x86_64-spark-3.5.0

USER root
# 1. Download Spark 4.0.1 (ensure correct version and Hadoop distro)
RUN wget -qO- https://archive.apache.org/dist/spark/spark-4.0.1/spark-4.0.1-bin-hadoop3.tgz | tar -xz -C /opt/
RUN rm -rf /usr/local/spark && rm -rf /usr/local/spark-3.5.0-bin-hadoop3
RUN ln -s /opt/spark-4.0.1-bin-hadoop3 /usr/local/spark

# 2. Set SPARK_HOME and PATH
#ENV SPARK_HOME=/opt/spark
#ENV PATH=$PATH:$SPARK_HOME/bin

# 3. Headless GUI stack so Apache Guacamole (deployed in-cluster) can stream
#    notebook GUIs to a web browser. Xvfb provides a virtual framebuffer,
#    fluxbox a minimal window manager, x11vnc exposes :1 over RFB on 5901.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        xvfb \
        x11vnc \
        fluxbox \
        xterm \
 && rm -rf /var/lib/apt/lists/*

# Firefox from Mozilla's official APT repo. Ubuntu's own `firefox` package on
# 22.04+ is a snap stub that won't work inside this container, so we add the
# Mozilla repo and pin it above the Ubuntu transitional package.
RUN install -d -m 0755 /etc/apt/keyrings \
 && wget -qO- https://packages.mozilla.org/apt/repo-signing-key.gpg \
        > /etc/apt/keyrings/packages.mozilla.org.asc \
 && echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
        > /etc/apt/sources.list.d/mozilla.list \
 && printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' \
        > /etc/apt/preferences.d/mozilla \
 && apt-get update \
 && apt-get install -y --no-install-recommends firefox \
 && rm -rf /var/lib/apt/lists/*

# Hook script: jupyter docker-stacks sources every *.sh in this directory
# from start-notebook.sh, so DISPLAY=:1 propagates into the notebook server.
COPY start-vnc.sh /usr/local/bin/before-notebook.d/0-start-vnc.sh
RUN chmod +x /usr/local/bin/before-notebook.d/0-start-vnc.sh

# 4. Install the matching PySpark package (for the Python client)
USER $NB_UID
#RUN conda remove pyspark && conda install -c conda-forge pyspark=4.0.0
RUN pip install --no-cache-dir pyspark==4.0.1 grpcio>=1.48.1 grpcio-status
