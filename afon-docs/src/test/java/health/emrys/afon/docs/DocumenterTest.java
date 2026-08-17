package health.emrys.afon.docs;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

public class DocumenterTest {

    private static AfonPropertyProvider providerOf(Properties properties) {
        return new AfonPropertyProvider() {
            @Override
            public Properties getProperties() {
                return properties;
            }

            @Override
            public Properties getProperties(String overrideFile) {
                return properties;
            }

            @Override
            public Properties getSecretProperties() {
                return properties;
            }

            @Override
            public String getValue(AfonProperty property) {
                return property.getValueFrom(properties);
            }

            @Override
            public String getValue(AfonProperty property, String defaultValue) {
                return property.getValueFrom(properties, defaultValue);
            }
        };
    }

    @Test
    public void isDocsDirCreated() {
        Documenter documenter = new Documenter(new Properties());
        assertTrue(documenter.docsDir().exists());
    }

    @Test
    public void canRedactSecrets() {
        Properties properties = new Properties();
        properties.setProperty("AWS_POLARIS_DOMAIN_NAME", "domain_name");
        DeployAwsFullStackDocument document = new DeployAwsFullStackDocument(providerOf(properties));
        Properties secretProperties = new Properties();
        secretProperties.setProperty("POLARIS_SECRET", "s3cr3t");
        Documenter documenter = new Documenter(secretProperties);
        assertThat(documenter.getContent(document)).contains("<polaris_secret>");
    }

    // TODO:  replace with Mockito
    private DocumentMetadata mockDocumentMetadata() {
        return new AbstractDocument(providerOf(new Properties())) {
            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public List<Step> steps(StepOperations stepOperations) {
                return List.of();
            }

            @Override
            protected String getSupplementaryIntro() {
                return "";
            }
        };
    }
}