package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

public class DocumenterTest {


    @Test
    public void isDocsDirCreated() {
        Documenter documenter = new Documenter(new FilePropertyProvider());
        assertTrue(documenter.docsDir().exists());
    }

    @Test
    public void canRedactSecrets() {
        AfonPropertyProvider properties = new FilePropertyProvider("src/test/resources/config", List.of("test", "secret"));
        DocumentMetadata document = new DeployAwsFullStackDocument(properties);
        Documenter documenter = new Documenter(properties);
        assertThat(documenter.getContent(document)).contains("<polaris_secret>");
    }

}