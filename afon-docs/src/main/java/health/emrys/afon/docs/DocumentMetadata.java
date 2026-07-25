package health.emrys.afon.docs;

import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;

import java.util.List;

public interface DocumentMetadata {
    String getDescription();

    String getFilename();

    List<Step> steps(StepOperations stepOperations);

    String getFurtherNotes();
}
