package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class StepRunner {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepRunner.class);
    /**
     * The env var that names a file to write the name of the step that failed to, which the command that runs this one reads, eg {@code manage-project-stack}, to say
     * which step to resume from.
     */
    public static final String FAILED_STEP_FILE = "AFON_FAILED_STEP_FILE";

    private final StepOperations operations;
    private final Path failedStepFile;

    public StepRunner(AfonUserProvider users) {
        this(new StepOperations(new StepActions(), new OpsPropertyProvider(), users), fileFromEnv());
    }

    StepRunner(StepOperations operations, Path failedStepFile) {
        this.operations = operations;
        this.failedStepFile = failedStepFile;
    }

    private static Path fileFromEnv() {
        String file = System.getenv(FAILED_STEP_FILE);
        return file == null || file.isBlank() ? null : Path.of(file);
    }

    /**
     * Says which step failed, on the console as well as in the log, as the console logging may be off, and in the file that the caller gave, if it did,
     * so that the failure says what to resume from.
     */
    private void reportFailedStep(String name) {
        System.err.println("Failed at step " + name + ": resume from it with manage-stack <action> <stack> " + name);
        if (failedStepFile != null) {
            try {
                Files.writeString(failedStepFile, name);
            } catch (IOException e) {
                logger.warn("Could not write the failed step {} to {}: {}", name, failedStepFile, e.getMessage());
            }
        }
    }

    /**
     * Run steps using the given provider
     *
     * @param stepsProvider the steps provider
     * @param startingWith the first step to run, if provided
     */
    public void runUsing(StepsProvider stepsProvider, String[] startingWith) {
        List<Step> steps = stepsProvider.stepsUsing(operations);
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
        logger.info("Steps to run: {}", join(steps));
            Cleanups cleanups = new Cleanups();
            for (Step step : steps) {
                try {
                    if (!Step.NOOP_NAME.equals(step.getName())) {
                        logger.info("Running step {}", step.getName());
                        cleanups.started(step);
                        step.getFunction().run();
                        cleanups.completed(step);
                    }
                } catch (Exception e) {
                    logger.error("Failed running step "+step.getName(), e);
                    reportFailedStep(step.getName());
                    cleanups.runPending();
                    throw new RuntimeException(e);
                }
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
            // a failure, not a run of no steps that succeeds: a misspelt step would otherwise look as if what is after it had been done
            throw new IllegalArgumentException("No step " + startingWith + " to resume from.  The steps are: " + String.join(", ", steps.stream().map(Step::getName).toList()));
        } else {
            runSteps(steps.subList(index, steps.size()));
        }
    }

    static @NonNull String join(List<Step> steps) {
        return String.join(" > ", steps.stream().map(x -> x.getName()).toList());
    }

}
