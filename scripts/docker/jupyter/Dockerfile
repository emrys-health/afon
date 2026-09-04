# Uses Spark 4
# Build it with:
# docker build -t ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi_pkg .
# docker push ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi_pkg
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
        autocutsel \
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

# Hook scripts: jupyter docker-stacks sources every *.sh in this directory from
# start-notebook.sh (before exec'ing the main command), so DISPLAY=:1 propagates and any
# backgrounded servers start. 0-start-vnc brings up the X/VNC desktop; 1-start-local-lab
# backgrounds the loopback-only Jupyter the user works in via that desktop (localhost:8888).
COPY start-vnc.sh /usr/local/bin/before-notebook.d/0-start-vnc.sh
COPY start-local-lab.sh /usr/local/bin/before-notebook.d/1-start-local-lab.sh
RUN chmod +x /usr/local/bin/before-notebook.d/0-start-vnc.sh \
             /usr/local/bin/before-notebook.d/1-start-local-lab.sh

# 4. Install the matching PySpark package (for the Python client)
USER $NB_UID
#RUN conda remove pyspark && conda install -c conda-forge pyspark=4.0.0
RUN pip install --no-cache-dir pyspark==4.0.1 grpcio\>=1.48.1 grpcio-status

# 5. JupyterHub support. Under JupyterHub, KubeSpawner runs `jupyterhub-singleuser`
#    (not the plain notebook server) and waits for it to register back with the hub.
#    That binary ships with the `jupyterhub` package, which the base pyspark-notebook
#    image does NOT include — without it the pod starts a plain notebook that never
#    phones home, so every spawn fails with "Spawn failed: Timeout". Any 4.x
#    single-user server is wire-compatible with the 4.x hub (jupyterhub/k8s-hub:3.3.8).
#    The VNC hook in before-notebook.d still runs, since start.sh sources it before
#    exec'ing jupyterhub-singleuser. It is installed in the block below.
#
# 6. Analysis stack, pinned. The base image is Python 3.11 with a conda-built
#    numpy 1.24 ABI underneath (scipy, matplotlib, sklearn, ...), so everything
#    here must stay on numpy 1.x. That ceiling is what makes exact pins
#    necessary rather than ranges:
#      - dowhy >= 0.14 requires numpy > 2.0, so dowhy is capped at 0.13, which
#        in turn requires cvxpy < 1.5;
#      - pymc/pytensor dropped numpy 1.x after pytensor 2.27.1, which caps pymc
#        at 5.20.1;
#      - scipy/arviz/pymc releases newer than these need Python >= 3.12.
#    With open ranges pip backtracks across all of that, walks off the end of
#    the wheel-bearing releases and starts building decade-old cvxpy sdists,
#    which die with "error in cvxpy setup command: use_2to3 is invalid".
#    --only-binary=:all: is the belt-and-braces guard: fail fast instead of
#    compiling an sdist. If any of these are bumped, bump them as a set.
RUN pip install --no-cache-dir --only-binary=:all: \
    "jupyterhub==4.1.6" \
    "numpy==1.26.4" \
    "pandas==2.2.3" \
    "scipy==1.15.3" \
    "scikit-learn==1.6.1" \
    "statsmodels==0.14.4" \
    "networkx==3.4.2" \
    "numba==0.60.0" \
    "cvxpy==1.4.4" \
    "dowhy==0.13" \
    "pytensor==2.27.1" \
    "pymc==5.20.1" \
    "arviz==0.21.0" \
    "matplotlib==3.10.0" \
    "seaborn==0.13.2" \
    "pyarrow==17.0.0" \
    "pytest==8.3.4" \
    "ruff==0.9.6"
