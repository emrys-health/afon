package health.emrys.afon.ops.steps;

import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class Step {

    private static final Logger logger = getLogger(Step.class);

    public static final String NOOP_NAME = "noop";
    public static final Step   NoOp      = new Step(
        NOOP_NAME, "No operation", new ThrowableRunnable() {
        @Override
        public void run() {
            logger.debug("No operation");
        }
    });

    private final String            name;
    private final String            description;
    private final ThrowableRunnable function;

    public Step(String name, String description, ThrowableRunnable function) {
        this.name        = name;
        this.description = description;
        this.function    = function;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ThrowableRunnable getFunction() {
        return function;
    }

    @Override
    public String toString() {
        return "Step{" +
               "name='" + name + '\'' +
               ", description='" + description + '\'' +
               '}';
    }

    /**
     * Composes steps into one that runs them in order, under an explicit name (rather than the
     * concatenation of the names of the steps, which grows unreadable).
     */
    public static Step sequence(String name, String description, Step... steps) {
        return new Step(
            name, description, () -> {
            for (Step step : steps) {
                step.function.run();
            }
        });
    }

    public Step andThen(Step other) {
        return new Step(
            String.format("%s_then_%s", this.name, other.name),
            description + " then " + other.description,
            () -> {
                this.function.run();
                other.function.run();
            });
    }
}
