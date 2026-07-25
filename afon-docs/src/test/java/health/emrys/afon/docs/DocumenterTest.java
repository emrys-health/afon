package health.emrys.afon.docs;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DocumenterTest {

    @Test
    public void testDocumentDirectoryAlreadyExists() {
        assertTrue(Documenter.documentationPath().toFile().exists());
    }

}