package health.emrys.afon.ops.flow;

import health.emrys.afon.ops.steps.Cleanups;
import org.slf4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Runs the steps of a flow in order, stopping at the first failure.
 */
public class FlowRunner {

    private static final Logger logger = getLogger(FlowRunner.class);

    /**
     * Thrown when a step fails, saying which items did not run and the step to resume from.
     */
    public static class FlowFailedException extends RuntimeException {
        private final String resumeStep;

        FlowFailedException(String message, String resumeStep, Throwable cause) {
            super(message, cause);
            this.resumeStep = resumeStep;
        }

        public String resumeStep() {
            return resumeStep;
        }
    }

    /**
     * @param resumeFrom the {@link FlowStep#qualifiedName()} to resume from (inclusive), or {@code null} to run the whole flow
     * @throws IllegalArgumentException if there is no such step
     * @throws FlowFailedException      if a step fails
     */
    public void run(String flowName, List<FlowStep> steps, String resumeFrom) {
        int start = resumeFrom == null ? 0 : indexOf(steps, resumeFrom);
        logger.info("Flow {}: steps to run: {}", flowName,
                    steps.subList(start, steps.size()).stream().map(FlowStep::qualifiedName).collect(Collectors.joining(" > ")));
        Cleanups cleanups = new Cleanups();
        for (int i = start; i < steps.size(); i++) {
            FlowStep step = steps.get(i);
            logger.info("Running step {} of item {}", step.qualifiedName(), step.item());
            try {
                cleanups.started(step.step());
                step.step().getFunction().run();
                cleanups.completed(step.step());
            } catch (Exception e) {
                FlowFailedException failure = failure(flowName, steps, i, e);
                cleanups.runPending();
                throw failure;
            }
        }
    }

    private static int indexOf(List<FlowStep> steps, String qualifiedName) {
        return java.util.stream.IntStream.range(0, steps.size())
                                         .filter(i -> steps.get(i).qualifiedName().equals(qualifiedName))
                                         .reduce((first, last) -> last)
                                         .orElseThrow(() -> new IllegalArgumentException(
                                             "Step '" + qualifiedName + "' not found, expected <item>:<step>, one of: "
                                                 + steps.stream().map(FlowStep::qualifiedName).collect(Collectors.joining(", "))));
    }

    private static FlowFailedException failure(String flowName, List<FlowStep> steps, int failed, Exception cause) {
        FlowStep step = steps.get(failed);
        List<FlowItem> notRun = steps.subList(failed + 1, steps.size()).stream()
                                     .filter(s -> s.itemIndex() > step.itemIndex())
                                     .map(FlowStep::item).distinct().toList();
        String message = String.format(
            "Flow %s failed at step %s of item %d (%s). Items not run: %s. Resume with: manage-stack run %s %s",
            flowName, step.qualifiedName(), step.itemIndex(), step.item(),
            notRun.isEmpty() ? "none" : notRun.stream().map(FlowItem::label).collect(Collectors.joining(", ")),
            flowName, step.qualifiedName());
        logger.error(message, cause);
        return new FlowFailedException(message, step.qualifiedName(), cause);
    }
}
