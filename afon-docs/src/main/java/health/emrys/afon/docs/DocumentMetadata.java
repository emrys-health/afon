package health.emrys.afon.docs;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public interface DocumentMetadata {

    String getDescription();

    String getFilename();

    String getFurtherNotes();

    List<Step> steps(StepOperations stepOperations);

    /**
     * @return the heading of the section starting with the step, eg the item of a flow it belongs to, or {@code null} if none
     */
    default String sectionHeading(Step step) {
        return null;
    }

}
