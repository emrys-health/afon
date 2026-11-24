package health.emrys.afon.spark.guard;

import org.apache.spark.sql.SparkSessionExtensions;
import scala.runtime.AbstractFunction1;
import scala.runtime.BoxedUnit;

/**
 * Adds {@link PlanPolicy} to the checks of every session's analysis.  Register it with {@code spark.sql.extensions}.
 *
 * <p>Spark only warns of an extension that it cannot load, and carries on without it, so it is in the same jar as
 * {@link SparkConnectGuardInterceptor}, without which the Spark Connect server does not start: if the server starts, this is there.
 */
public class SparkGuardExtensions extends AbstractFunction1<SparkSessionExtensions, BoxedUnit> {

    @Override
    public BoxedUnit apply(SparkSessionExtensions extensions) {
        extensions.injectCheckRule(session -> plan -> {
            PlanPolicy.check(plan);
            return BoxedUnit.UNIT;
        });
        return BoxedUnit.UNIT;
    }
}
