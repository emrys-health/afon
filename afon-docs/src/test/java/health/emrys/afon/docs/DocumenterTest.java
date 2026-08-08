package health.emrys.afon.docs;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DocumenterTest {

    @Test
    public void isDocsDirCreated() {
        Documenter documenter = new Documenter(new DocumentAwsFullStackMain());
        assertTrue(documenter.docsDir().exists());
    }

    @Test
    public void canRedactSecrets() {
        Documenter documenter = new Documenter(new DocumentAwsFullStackMain());
        documenter.writeDocumentation();
    }

}