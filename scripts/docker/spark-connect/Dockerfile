# Build this with:
# mvn -pl afon-spark-additional -am package -DskipTests && cp afon-spark-additional/target/afon-spark-additional-*.jar scripts/docker/spark-connect/
# docker build -t emryshealth/spark_jars:4.0.1 .
# docker push emryshealth/spark_jars:4.0.1

# See SparkWorkloads.SPARK_IMAGE
ARG UV_VERSION=0.12.15
FROM ghcr.io/astral-sh/uv:${UV_VERSION} AS uv

FROM apache/spark:4.0.1

# Switch to root to modify the internal directories and install tools if needed
USER root

# Set environment variables for readability
ENV SPARK_VERSION=4.0.1
ENV SCALA_VERSION=2.13

# Use Spark's built-in spark-submit tool to fetch the Spark Connect packages.
# We redirect it to the standard Spark jars directory (/opt/spark/jars)
# so they are automatically loaded into the classpath at runtime.
RUN /opt/spark/bin/spark-submit \
    --packages org.apache.spark:spark-connect_${SCALA_VERSION}:${SPARK_VERSION} \
    --repositories https://repo1.maven.org/maven2/ \
    --conf spark.jars.ivy=/tmp/.ivy \
    --packages=com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.1,com.google.cloud:google-cloud-storage:2.57.0,software.amazon.awssdk:bundle:2.24.6,org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0,org.apache.hadoop:hadoop-azure:3.4.1,com.azure:azure-storage-file-datalake:12.22.0 \
    --class org.apache.spark.deploy.SparkSubmit \
    /opt/spark/examples/src/main/python/pi.py 10 && \
    mv /tmp/.ivy/jars/* /opt/spark/jars/ && \
    rm -rf /tmp/.ivy

# What SparkWorkloads registers: the audit listener (spark.sql.queryExecutionListeners), without which no session can start, and
# the gRPC interceptor and the session extension, which let into Spark Connect only a client with an OIDC token of the realm, and
# refuse JVM code: without the interceptor the Spark Connect server does not start
COPY afon-spark-additional-*.jar /opt/spark/jars/

# The Python that runs the users' Python UDFs, on the executors.  PySpark refuses a UDF from a client whose Python is of another minor
# version (PYTHON_VERSION_MISMATCH), and the clients are the notebooks of afon-desktop (scripts/docker/afon/desktop), whose
# jupyter/pyspark-notebook base has Python 3.11, while the Python of this image's Ubuntu 22.04 is 3.10.  So keep PYTHON_VERSION the
# minor version of the notebooks' Python, and the libraries the versions of theirs, which pandas UDFs and Arrow need on the executors.
# Spark Connect's server takes the path of the Python of the executors from PYSPARK_PYTHON, in its own environment: the driver and
# the workers run this same image.
ARG PYTHON_VERSION=3.11
ENV UV_PYTHON_INSTALL_DIR=/opt/uv/python
COPY --from=uv /uv /usr/local/bin/
RUN uv venv --python ${PYTHON_VERSION} /opt/pyspark-python \
 && uv pip install --python /opt/pyspark-python/bin/python --no-cache numpy==1.24.4 pandas==2.0.3 pyarrow==13.0.0 \
 && chmod -R a+rX /opt/uv /opt/pyspark-python \
 && rm /usr/local/bin/uv \
 && /opt/pyspark-python/bin/python --version
ENV PYSPARK_PYTHON=/opt/pyspark-python/bin/python

# Switch back to the default non-root user (spark) for security
USER 185

# Expose the default Spark Connect gRPC port
EXPOSE 15002

# Set the default command to launch the Spark Connect server directly
#ENTRYPOINT ["/opt/spark/sbin/start-connect-server.sh", "--wait"]