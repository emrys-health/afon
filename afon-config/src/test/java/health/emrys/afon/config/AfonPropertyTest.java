package health.emrys.afon.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;

import static health.emrys.afon.config.AfonProperties.PROJECT_PROPERTIES_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AfonPropertyTest {
    @BeforeAll
    public static void initializeConstants() throws ClassNotFoundException {
        Class.forName(AfonProperties.class.getName());
    }

    @Test
    public void testAllProperties() {
        assertThat(AfonProperty.getAllProperties()).hasSizeGreaterThan(0);
    }

    @Test
    public void testAllPropertiesInProjectConfigFileAreRecognised() throws IOException {
        Properties properties = new Properties();
        properties.load(new FileInputStream("../" + PROJECT_PROPERTIES_FILE));
        checkAllExpectedInPropertiesFile(properties);
        checkOnlyExpectedInPropertiesFile(properties);
        checkExpectedValuesNotEmpty(properties);
    }

    private void checkExpectedValuesNotEmpty(Properties properties) {
        for (String property : AfonProperty.getAllProperties()) {
            assertThat(properties.get(property)).isNotEqualTo("");
        }
    }

    private static void checkOnlyExpectedInPropertiesFile(Properties properties) {
        Set<String> expectedProperties = AfonProperty.getAllProperties();
        for (Object property : properties.keySet()) {
            assertTrue(
                    expectedProperties.contains(property.toString()),
                    property.toString() + " not in " + expectedProperties);
        }
    }

    private static void checkAllExpectedInPropertiesFile(Properties properties) {
        for (String property : AfonProperty.getAllProperties()) {
            assertThat(properties.get(property)).isNotNull();
        }
    }

}