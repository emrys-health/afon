package health.emrys.afon.ops.demo;

import health.emrys.afon.spark.SparkWarehouseConfig;

import java.util.Map;
import java.util.TreeMap;

import static java.lang.String.format;

/**
 * Generates the PySpark code that creates a Spark Connect session whose catalogs authenticate with
 * the user's own OIDC token, read from the {@code OIDC_TOKEN} environment variable injected into
 * the Jupyter pod. The shared Spark Connect server registers no catalogs of its own.
 */
class PySparkSessionCode {

    static final String OIDC_TOKEN = "OIDC_TOKEN";

    private final SparkWarehouseConfig config;

    PySparkSessionCode(SparkWarehouseConfig config) {
        this.config = config;
    }

    /**
     * @param catalogs the Spark catalog names mapped to their Polaris catalog names.
     */
    String sessionCode(Map<String, String> catalogs) {
        StringBuilder python = new StringBuilder("""
                                                 from pyspark.sql import SparkSession
                                                 import os as os

                                                 OIDC_TOKEN = os.environ["OIDC_TOKEN"]
                                                 spark = (SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002")
                                                 """);
        catalogs.forEach((sparkCatalog, catalogName) -> {
            python.append(format("# %s \n", catalogName));
            new TreeMap<>(config.sparkConfigsWithToken(sparkCatalog, catalogName, OIDC_TOKEN))
                .forEach((key, value) -> python.append(key.endsWith(".token")
                                                           ? format(".config(\"%s\", %s)\n", key, value)
                                                           : format(".config(\"%s\", \"%s\")\n", key, value)));
            python.append("\n");
        });
        python.append("""
                      ).getOrCreate()

                      print("Finished initializing spark session")
                      """);
        return python.toString();
    }
}
