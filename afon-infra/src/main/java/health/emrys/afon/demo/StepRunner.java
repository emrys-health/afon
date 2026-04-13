package health.emrys.afon.demo;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

public class StepRunner {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(StepRunner.class);

    void runFrom(List<Step> steps, String firstStepToRun) {
        log.info("Running everything from {} (inclusive) onwards out of {}", steps, humanReadable(steps));
        int    index = steps.stream().map(s -> s.name).toList().lastIndexOf(firstStepToRun);
        if (index == -1) {
            log.error("Step {} not found", firstStepToRun);
        } else {
            runSteps(steps.subList(index, steps.size()));
        }
    }

    void runSteps(List<Step> steps) {
        log.info("StepOperations:\n{}", humanReadable(steps));
        try {
            for (Step step : steps) {
                log.info("Running step {}", step.name);
                step.function.run();
            }
            log.info("Successfully ran steps\n{}", humanReadable(steps));
        } catch (Exception e) {
            log.error("Error running steps", e);
        }
    }

    static @NonNull String humanReadable(List<Step> steps) {
        return String.join("\n", steps.stream().map(x -> x.name).toList());
    }

}
