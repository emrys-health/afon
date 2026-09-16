package health.emrys.afon.ops.spark;

import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class TableUtils {

    private static final Logger logger = getLogger(TableUtils.class);

    static @NonNull String registerTable(SparkSession spark, String catalogName, String namespace, String tableName) {
        String namespaceFQN = namespaceFQN(catalogName, namespace);
        logger.info("Creating namespace {}", namespaceFQN);
        String namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s", namespaceFQN);
        logger.info("Executing: {}", namespaceSql);
        spark.sql(namespaceSql);

        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Creating table {}", tableFQN);
        String createTableSql = format("CREATE TABLE IF NOT EXISTS %s (value String) USING iceberg ", tableFQN);
        logger.info("Executing: {}", createTableSql);
        spark.sql(createTableSql);
        return tableFQN;
    }

    protected static @NonNull String namespaceFQN(String catalogName, String namespace) {
        return catalogName + "." + namespace;
    }

    protected static @NonNull String tableFQN(String catalogName, String namespace, String tableName) {
        return catalogName + "." + namespace + "." + tableName;
    }

}
