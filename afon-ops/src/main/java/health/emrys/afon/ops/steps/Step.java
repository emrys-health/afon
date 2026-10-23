package health.emrys.afon.ops.steps;

import org.slf4j.Logger;

import java.util.Arrays;

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
    private final Step              cleanup;
    private final boolean           callingPolaris;

    public Step(String name, String description, ThrowableRunnable function) {
        this(name, description, function, null, false);
    }

    private Step(String name, String description, ThrowableRunnable function, Step cleanup, boolean callingPolaris) {
        this.name           = name;
        this.description    = description;
        this.function       = function;
        this.cleanup        = cleanup;
        this.callingPolaris = callingPolaris;
    }

    /**
     * @param cleanup a step that undoes this one, which also comes later in the steps, eg restoring what this one relaxed: if a step
     *                fails after this one started, the runners run it before reporting the failure, see {@link Cleanups}
     * @return this step with the cleanup
     */
    public Step withCleanup(Step cleanup) {
        return new Step(name, description, function, cleanup, callingPolaris);
    }

    /**
     * @return the step to run if a step fails after this one started, or {@code null} if there is none
     */
    public Step getCleanup() {
        return cleanup;
    }

    /**
     * @return this step, marked as calling the REST API of Polaris from the machine running it, ie not from the cluster, which the
     * ingress of Polaris may restrict, so that a plan can admit the machine, if it needs to
     */
    public Step callingPolaris() {
        return new Step(name, description, function, cleanup, true);
    }

    /**
     * @return whether the step calls the REST API of Polaris from the machine running it, or is composed of steps that do
     */
    public boolean isCallingPolaris() {
        return callingPolaris;
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
        }, null, Arrays.stream(steps).anyMatch(Step::isCallingPolaris));
    }

    public Step andThen(Step other) {
        return new Step(
            String.format("%s_then_%s", this.name, other.name),
            description + " then " + other.description,
            () -> {
                this.function.run();
                other.function.run();
            }, null, callingPolaris || other.callingPolaris);
    }
}
