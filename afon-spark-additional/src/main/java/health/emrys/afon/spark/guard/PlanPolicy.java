package health.emrys.afon.spark.guard;

import org.apache.spark.sql.catalyst.analysis.GlobalTempView$;
import org.apache.spark.sql.catalyst.expressions.CallMethodViaReflection;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.TryReflect;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.command.AddArchivesCommand;
import org.apache.spark.sql.execution.command.AddFilesCommand;
import org.apache.spark.sql.execution.command.AddJarsCommand;
import org.apache.spark.sql.execution.command.CreateFunctionCommand;
import org.apache.spark.sql.execution.command.CreateViewCommand;
import scala.runtime.BoxedUnit;

import java.util.Optional;
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
 *   <li>no {@code reflect}, {@code java_method} or {@code try_reflect}, which call any static method of the JVM.</li>
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
                refusalOf(node).ifPresent(refusal::set);
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
}
