package health.emrys.afon.ops.steps;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Keeps, while the steps run, the {@link Step#getCleanup() cleanups} that are pending, ie those of the steps that started and whose
 * cleanup has not run as a step of its own yet, like a {@code finally} around the steps in between: when a step fails, a runner runs
 * them, latest first, before reporting the failure.
 */
public class Cleanups {

    private static final Logger logger = getLogger(Cleanups.class);

    private final Deque<Step> pending = new ArrayDeque<>();

    /**
     * To call just before the step runs: its cleanup is pending, even if it is the step that fails, as it may have done part of what it does.
     */
    public void started(Step step) {
        if (step.getCleanup() != null) {
            pending.push(step.getCleanup());
        }
    }

    /**
     * To call after the step ran: if it is the cleanup of an earlier step, as the steps have it later too, it is no longer pending.
     */
    public void completed(Step step) {
        pending.removeIf(cleanup -> cleanup.getName().equals(step.getName()));
    }

    /**
     * Runs the pending cleanups, latest first. A failing cleanup is logged and the others still run, as it is the failure of the step that is reported.
     */
    public void runPending() {
        while (!pending.isEmpty()) {
            Step cleanup = pending.pop();
            logger.warn("Running step {} to clean up after the failure", cleanup.getName());
            try {
                cleanup.getFunction().run();
            } catch (Exception e) {
                logger.error("Failed running step " + cleanup.getName() + " to clean up after the failure", e);
            }
        }
    }
}
