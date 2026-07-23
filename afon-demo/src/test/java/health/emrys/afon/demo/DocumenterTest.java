package health.emrys.afon.demo;

import org.junit.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DocumenterTest {

    @Test
    public void testDocumentDirectoryAlreadyExists() {
        assertTrue(Documenter.documentationPath().toFile().exists());
    }

}