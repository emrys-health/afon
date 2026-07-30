package health.emrys.afon.ops.steps;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

public class StepRunner {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepRunner.class);

    /**
     * Run steps using the given provider
     *
     * @param stepsProvider the steps provider
     * @param startingWith the first step to run, if provided
     */
    public void runUsing(StepsProvider stepsProvider, String[] startingWith) {
        List<Step> steps = stepsProvider.stepsUsing(new StepOperations(new StepActions()));
        if (startingWith.length == 0) {
            runSteps(steps);
        } else {
            runStepsStartingWith(steps, startingWith[0]);
        }
    }

    /**
     * Runs a list of steps
     * @param steps
     */
    public void runSteps(List<Step> steps) {
        logger.info("\u001B[32mSteps to run: {}\u001B[0m", join(steps));
        try {
            for (Step step : steps) {
                logger.info("\u001B[32mRunning step {}\u001B[0m", step.getName());
                step.getFunction().run();
            }
        } catch (Exception e) {
            logger.error("Failed running steps", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs a list of steps starting with a given one
     * @param steps
     * @param startingWith
     */
    public void runStepsStartingWith(List<Step> steps, String startingWith) {
        logger.info("Running all steps starting with {} (inclusive) from {}", startingWith, join(steps));
        int    index = steps.stream().map(s -> s.getName()).toList().lastIndexOf(startingWith);
        if (index == -1) {
            logger.error("Step {} not found", startingWith);
        } else {
            runSteps(steps.subList(index, steps.size()));
        }
    }

    static @NonNull String join(List<Step> steps) {
        return String.join(" > ", steps.stream().map(x -> x.getName()).toList());
    }

}
