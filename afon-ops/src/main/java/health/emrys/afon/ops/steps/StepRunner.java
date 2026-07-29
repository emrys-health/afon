package health.emrys.afon.ops.steps;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

public class StepRunner {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepRunner.class);

    public void run(StepsProvider stepsProvider, String[] args) {
        List<Step> steps = stepsProvider.stepsUsing(new StepOperations(new StepActions()));
        if (args.length == 0) {
            runSteps(steps);
        } else {
            String firstStep  = args[0];
            runFrom(steps, firstStep);
        }
    }

    public void runFrom(List<Step> steps, String firstStepToRun) {
        logger.info("Running all steps from {} (inclusive) onwards out of {}", humanReadable(steps), firstStepToRun);
        int    index = steps.stream().map(s -> s.getName()).toList().lastIndexOf(firstStepToRun);
        if (index == -1) {
            logger.error("Step {} not found", firstStepToRun);
        } else {
            runSteps(steps.subList(index, steps.size()));
        }
    }

    public void runSteps(List<Step> steps) {
        logger.info("\u001B[32mSteps to run: {}\u001B[0m", humanReadable(steps));
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

    static @NonNull String humanReadable(List<Step> steps) {
        return String.join(" > ", steps.stream().map(x -> x.getName()).toList());
    }

}
