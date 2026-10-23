# Build this with:
# docker build -t emryshealth/spark_jars:4.0.1 .
# docker push emryshealth/spark_jars:4.0.1

# See SparkWorkloads.SPARK_IMAGE
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

# Switch back to the default non-root user (spark) for security
USER 185

# Expose the default Spark Connect gRPC port
EXPOSE 15002

# Set the default command to launch the Spark Connect server directly
#ENTRYPOINT ["/opt/spark/sbin/start-connect-server.sh", "--wait"]