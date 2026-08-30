package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultPropertyProviderTest {

    static class TestDefaults {
        public static final String AWS_REGION = "default-region";
    }

    @Test
    public void fallsBackToConstantWhenNotConfigured() {
        // redirect from the real $AFON_HOME/config so this doesn't depend on the local env config
        System.setProperty(ConfigUtils.AFON_HOME_PROPERTY, "src/test/resources/empty");
        try {
            AfonPropertyProvider provider = new DefaultPropertyProvider(TestDefaults.class);
            assertThat(provider.getValue(AfonProperty.AWS_REGION)).isEqualTo(TestDefaults.AWS_REGION);
        } finally {
            System.clearProperty(ConfigUtils.AFON_HOME_PROPERTY);
        }
    }

    @Test
    public void systemPropertyOverridesConstantDefault() {
        String systemRegion = "system-region";
        System.setProperty(AfonProperty.AWS_REGION.getName(), systemRegion);
        try {
            AfonPropertyProvider provider = new DefaultPropertyProvider(TestDefaults.class);
            assertThat(provider.getValue(AfonProperty.AWS_REGION)).isEqualTo(systemRegion);
        } finally {
            System.clearProperty(AfonProperty.AWS_REGION.getName());
        }
    }

}
