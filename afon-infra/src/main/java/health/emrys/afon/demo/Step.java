package health.emrys.afon.demo;

import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class Step {

    private static final Logger log = getLogger(Step.class);

    public static final Step NoOp = new Step(
        "noop", "No operation", new ThrowableRunnable() {
        @Override
        public void run() {
            log.debug("No operation");
        }
    });

    final String            name;
    final String            description;
    final ThrowableRunnable function;

    public Step(String name, String description, ThrowableRunnable function) {
        this.name        = name;
        this.description = description;
        this.function    = function;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "Step{" +
            "name='" + name + '\'' +
            ", description='" + description + '\'' +
            '}';
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
