package health.emrys.afon.spark.guard;

import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.catalyst.analysis.GlobalTempView$;
import org.apache.spark.sql.catalyst.catalog.CatalogStorageFormat;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.catalyst.catalog.CatalogTableType;
import org.apache.spark.sql.catalyst.expressions.CallMethodViaReflection;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.TryReflect;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.TableSpecBase;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import org.apache.spark.sql.execution.command.AddArchivesCommand;
import org.apache.spark.sql.execution.command.AddFilesCommand;
import org.apache.spark.sql.execution.command.AddJarsCommand;
import org.apache.spark.sql.execution.command.CreateFunctionCommand;
import org.apache.spark.sql.execution.command.CreateViewCommand;
import org.apache.spark.sql.execution.datasources.DataSource;
import org.apache.spark.sql.execution.datasources.HadoopFsRelation;
import org.apache.spark.sql.execution.datasources.v2.FileTable;
import scala.Option;
import scala.jdk.javaapi.CollectionConverters;
import scala.runtime.BoxedUnit;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What Spark refuses of a query once it has analysed it, whichever way it came: SQL, a DataFrame, or SQL built at run time and
 * run with {@code EXECUTE IMMEDIATE}, which no check of a request's text could see.  The users of the one Spark Connect server
 * share its driver and its executors, so it runs nothing that would let one of them reach the others' work:
 * <ul>
 *   <li>no {@code ADD JAR}, {@code ADD FILE} or {@code ADD ARCHIVE}, which would bring code onto the driver and the executors;</li>
 *   <li>no function made from a class, with {@code CREATE FUNCTION ... AS '<class>'}, which may name a jar to load it from too.  A
 *       function written in SQL is a command of its own, and is let through;</li>
 *   <li>no global temporary view, which every session can read, with the credentials of the session that made it;</li>
 *   <li>no {@code reflect}, {@code java_method} or {@code try_reflect}, which call any static method of the JVM;</li>
 *   <li>no file read or written but in the clouds' object stores, see {@link Locations}: not from SQL, such as
 *       {@code SELECT * FROM text.`/etc/hostname`} or {@code INSERT OVERWRITE DIRECTORY}, nor from a DataFrame.  Nor a table of
 *       {@code spark_catalog} with no location, which is kept on the local disk of the server, in a catalog that every session
 *       shares: a user's tables belong in their own catalog.</li>
 * </ul>
 * The JVM code that a request may hold, before Spark reads it, is refused by {@link RequestPolicy}.
 */
final class PlanPolicy {

    private PlanPolicy() {
    }

    /**
     * @throws SecurityException if the plan does what is refused
     */
    static void check(LogicalPlan plan) {
        refusal(plan).ifPresent(why -> {
            throw new SecurityException(why);
        });
    }

    static Optional<String> refusal(LogicalPlan plan) {
        AtomicReference<String> refusal = new AtomicReference<>();
        plan.foreachWithSubqueries(node -> {
            if (refusal.get() == null) {
                refusalOf(node).or(() -> locationRefusal(node)).ifPresent(refusal::set);
            }
            node.expressions().foreach(expression -> {
                ((Expression) expression).foreach(e -> {
                    if (refusal.get() == null && (e instanceof CallMethodViaReflection || e instanceof TryReflect)) {
                        refusal.set("reflect, java_method and try_reflect are refused, as they call any method of the JVM of the server, "
                                    + "which every user shares");
                    }
                    return BoxedUnit.UNIT;
                });
                return BoxedUnit.UNIT;
            });
            return BoxedUnit.UNIT;
        });
        return Optional.ofNullable(refusal.get());
    }

    private static Optional<String> refusalOf(LogicalPlan node) {
        if (node instanceof AddJarsCommand || node instanceof AddFilesCommand || node instanceof AddArchivesCommand) {
            return Optional.of("ADD JAR, ADD FILE and ADD ARCHIVE are refused, as the Spark Connect server runs no JVM code of its clients");
        }
        if (node instanceof CreateFunctionCommand) {
            return Optional.of("CREATE FUNCTION from a class is refused, as the Spark Connect server runs no JVM code of its clients: "
                               + "write the function in SQL or Python instead");
        }
        if (node instanceof CreateViewCommand view && GlobalTempView$.MODULE$.equals(view.viewType())) {
            return Optional.of("global temporary views are refused, as every session could read one, with the credentials of the "
                               + "session that made it: use a temporary view instead");
        }
        return Optional.empty();
    }

    /**
     * @return why a location that the node reads or writes is refused, looking into its fields but not into its children nor its
     * expressions, which are nodes of their own
     */
    private static Optional<String> locationRefusal(LogicalPlan node) {
        java.util.Iterator<Object> fields = CollectionConverters.asJava(node.productIterator());
        while (fields.hasNext()) {
            Optional<String> refused = locationRefusalIn(fields.next(), 0);
            if (refused.isPresent()) {
                return refused;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> locationRefusalIn(Object value, int depth) {
        if (value == null || depth > 3 || value instanceof TreeNode<?>) {
            return Optional.empty();
        }
        if (value instanceof Path || value instanceof URI) {
            return Locations.refusal(value.toString());
        }
        if (value instanceof CatalogTable table) {
            if (table.storage().locationUri().isEmpty() && !CatalogTableType.VIEW().equals(table.tableType())) {
                return Optional.of("the table " + table.identifier().unquotedString() + " is refused, as spark_catalog keeps it on the "
                                   + "local disk of the server, in a catalog that every user shares: write it to your own catalog");
            }
            return locationRefusalIn(table.storage(), depth + 1);
        }
        if (value instanceof CatalogStorageFormat storage) {
            return locationRefusalIn(storage.locationUri(), depth + 1);
        }
        if (value instanceof HadoopFsRelation relation) {
            return firstRefused(relation.location().rootPaths(), path -> Locations.refusal(path.toString()));
        }
        if (value instanceof FileTable table) {
            return firstRefused(table.fileIndex().rootPaths(), path -> Locations.refusal(path.toString()));
        }
        if (value instanceof DataSource source) {
            return firstRefused(source.paths(), Locations::refusal).or(() -> locationRefusalIn(source.options(), depth + 1));
        }
        if (value instanceof TableSpecBase spec) {
            return spec.location().isEmpty() ? Optional.empty() : Locations.refusal(spec.location().get());
        }
        if (value instanceof Option<?> option) {
            return option.isEmpty() ? Optional.empty() : locationRefusalIn(option.get(), depth + 1);
        }
        if (value instanceof scala.collection.Map<?, ?> map) {
            return locationRefusalIn(CollectionConverters.asJava(map), depth);
        }
        if (value instanceof java.util.Map<?, ?> map) {
            return map.entrySet().stream()
                      .filter(e -> e.getKey() instanceof String && (e.getValue() == null || e.getValue() instanceof String))
                      .map(e -> Locations.optionRefusal((String) e.getKey(), (String) e.getValue()))
                      .flatMap(Optional::stream)
                      .findFirst();
        }
        return Optional.empty();
    }

    private static <T> Optional<String> firstRefused(scala.collection.Iterable<T> locations, Function<T, Optional<String>> refusal) {
        for (T location : CollectionConverters.asJava(locations)) {
            Optional<String> refused = refusal.apply(location);
            if (refused.isPresent()) {
                return refused;
            }
        }
        return Optional.empty();
    }
}
