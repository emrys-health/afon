package health.emrys.afon.docs;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public interface DocumentMetadata {

    String getDescription();

    String getFilename();

    String getFurtherNotes();

    List<Step> steps(StepOperations stepOperations);

}
