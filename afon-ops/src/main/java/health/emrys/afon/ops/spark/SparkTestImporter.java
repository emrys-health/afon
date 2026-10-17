package health.emrys.afon.ops.spark;

import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static health.emrys.afon.ops.spark.TableUtils.createNamespace;
import static health.emrys.afon.ops.spark.TableUtils.createTable;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkTestImporter extends SparkRunner {

    private static final Logger logger = getLogger(SparkTestImporter.class);

    protected SparkTestImporter(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values) {
        logger.info("Parquet files to read {}", String.join(", ", values));
        if (values.size() != 1) {
            logger.warn("{} requires one dir. Got {}", SparkTestImporter.class.getSimpleName(), values.size());
            return;
        }
        String dir = values.get(0);
        logger.info("About to read parquet files from {}", dir);
        createNamespace(spark, catalogName, namespace);

        for (Path path : readParquetFilesFrom(dir)) {
            importTable(spark, catalogName, namespace, tableName(path), path.toAbsolutePath().toString());
        }
    }

    // use the name (with ext) of the path as table name
    private static @NonNull String tableName(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static void importTable(SparkSession spark, String catalogName, String namespace, String tableName, String path) {
        Dataset<Row> df = spark.read().parquet(path);
        String tableFQN = createTable(spark, catalogName, namespace, tableName, df);
        df.show();
        df.printSchema();
        df.write().mode("overwrite")   // or "append"
                .saveAsTable(tableFQN);
    }

    private List<Path> readParquetFilesFrom(String dir) {
        try (Stream<Path> stream = Files.list(Paths.get(dir))) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".parq"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        var properties = new OpsPropertyProvider();
        parseAndExecute(new SparkTestImporter(properties), properties, new FileUserProvider(), args);
    }
}
