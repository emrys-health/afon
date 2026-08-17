package health.emrys.afon.docs;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.GivenPropertyProvider;
import org.junit.Test;

import java.util.Properties;

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
        Properties properties = new Properties();
        properties.setProperty("AWS_POLARIS_DOMAIN_NAME", "domain_name");
        DeployAwsFullStackDocument document = new DeployAwsFullStackDocument(new GivenPropertyProvider(properties));
        Properties secretProperties = new Properties();
        secretProperties.setProperty("POLARIS_SECRET", "s3cr3t");
        Documenter documenter = new Documenter(new GivenPropertyProvider(secretProperties));
        assertThat(documenter.getContent(document)).contains("<polaris_secret>");
    }

}