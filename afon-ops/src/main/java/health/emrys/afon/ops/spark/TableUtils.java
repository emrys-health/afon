package health.emrys.afon.ops.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.Arrays;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class TableUtils {

    private static final Logger logger = getLogger(TableUtils.class);

    static @NonNull String registerTable(SparkSession spark, String catalogName, String namespace, String tableName) {
        createNamespace(spark, catalogName, namespace);
        return createTable(spark, catalogName, namespace, tableName);
    }

    static @NonNull String createTable(SparkSession spark,
                                       String catalogName,
                                       String namespace,
                                       String tableName,
                                       Dataset<Row> df) {
        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Creating table {}", tableFQN);
        String[] types = Arrays.stream(df.dtypes()).map(x -> x._1 + " " +
            x._2.replace("Type", "").replace("NTZ", "")
        ).toArray(String[]::new);
        String createTableSql = format("CREATE TABLE IF NOT EXISTS %s (%s) USING iceberg ", tableFQN, String.join(", ", types));
        logger.info("Executing: {}", createTableSql);
        spark.sql(createTableSql);
        return tableFQN;
    }

    static @NonNull String createTable(SparkSession spark, String catalogName, String namespace, String tableName) {
        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Creating table {}", tableFQN);
        String createTableSql = format("CREATE TABLE IF NOT EXISTS %s (value String) USING iceberg ", tableFQN);
        logger.info("Executing: {}", createTableSql);
        spark.sql(createTableSql);
        return tableFQN;
    }

    static void createNamespace(SparkSession spark,
                                String catalogName,
                                String namespace) {
        String namespaceFQN = namespaceFQN(catalogName, namespace);
        logger.info("Creating namespace {}", namespaceFQN);
        String namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s", namespaceFQN);
        logger.info("Executing: {}", namespaceSql);
        spark.sql(namespaceSql);
    }

    protected static @NonNull String namespaceFQN(String catalogName, String namespace) {
        return catalogName + "." + namespace;
    }

    protected static @NonNull String tableFQN(String catalogName, String namespace, String tableName) {
        return catalogName + "." + namespace + "." + tableName;
    }

}
