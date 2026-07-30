package health.emrys.afon.docs;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class DocumenterTest {

    @Test
    public void testDocumentDirectoryAlreadyExists() {
        assertTrue(Documenter.documentationPath().toFile().exists());
    }

}