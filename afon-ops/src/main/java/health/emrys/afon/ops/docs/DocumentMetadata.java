package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Set;

public interface DocumentMetadata {

    String getDescription();

    String getFilename();

    String getFurtherNotes();

    List<Step> steps(StepOperations stepOperations);

    /**
     * @return the clouds of the stacks the document is about, to document only those of the configured cloud
     */
    default Set<AfonCloud> clouds() {
        return Set.of();
    }

    /**
     * @return the heading of the section starting with the step, eg the item of a flow it belongs to, or {@code null} if none
     */
    default String sectionHeading(Step step) {
        return null;
    }

}
