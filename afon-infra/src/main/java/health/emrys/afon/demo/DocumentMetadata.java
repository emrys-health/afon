package health.emrys.afon.demo;

import java.util.List;

public interface DocumentMetadata {
    String getDescription();

    String getFilename();

    List<Step> steps(StepOperations stepOperations);

    String getFurtherNotes();
}
