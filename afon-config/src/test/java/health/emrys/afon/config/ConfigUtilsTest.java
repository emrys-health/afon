package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.ConfigUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigUtilsTest {

    @Test
    public void canRetrieveAfonHome() {
        System.setProperty(AFON_HOME_PROPERTY, "/afon/home");
        try {
            assertThat(afonHome()).isEqualTo("/afon/home");
        } finally {
            System.clearProperty(AFON_HOME_PROPERTY);
        }
        // default value
        assertThat(afonHome()).isEqualTo(System.getenv("HOME")+"/.afon");
    }

    @Test
    public void canOverrideAfonHomeViaEnv() {
        System.setProperty(AFON_HOME_PROPERTY, "/afon/home");
        try {
            assertThat(afonHome()).isEqualTo("/afon/home");
        } finally {
            System.clearProperty(AFON_HOME_PROPERTY);
        }
        // default value
        assertThat(afonHome()).isEqualTo(System.getenv("HOME")+"/.afon");
    }

    @Test
    public void canRetrieveAfonCdktf() {
        System.setProperty(AFON_CDKTF_PROPERTY, "/afon/cdktf");
        try {
            assertThat(afonCkdtf()).isEqualTo("/afon/cdktf");
        } finally {
            System.clearProperty(AFON_CDKTF_PROPERTY);
        }
        // default value
        assertThat(afonCkdtf()).isEqualTo(afonHome()+"/cdktf");
    }

    @Test
    public void canRetrieveAfonCloud() {
        System.setProperty(AFON_CLOUD_PROPERTY, "cloud");
        try {
            assertThat(afonCloud()).isEqualTo("cloud");
        } finally {
            System.clearProperty(AFON_CLOUD_PROPERTY);
        }
        // default value
        assertThat(afonCloud()).isEqualTo("");
    }

    @Test
    public void canRetrieveCloudProperty() {
        System.setProperty(AFON_CLOUD_PROPERTY, "aws");
        try {
            assertThat(cloudPropertySet()).isTrue();
            assertThat(cloudPropertyValue()).isEqualTo("aws");
        } finally {
            System.clearProperty(AFON_CLOUD_PROPERTY);
        }
        assertThat(cloudPropertySet()).isFalse();
        assertThat(cloudPropertyValue()).isNull();
    }

    @Test
    public void canRetrieveLoggingLevels() {
        System.setProperty(AFON_LOGGING_PROPERTY, "WARN");
        System.setProperty(AFON_LOGGING_CONSOLE_PROPERTY, "ERROR");
        try {
            assertThat(afonLoggingLevel()).isEqualTo("WARN");
            assertThat(afonLoggingConsoleLevel()).isEqualTo("ERROR");
        } finally {
            System.clearProperty(AFON_LOGGING_PROPERTY);
            System.clearProperty(AFON_LOGGING_CONSOLE_PROPERTY);
        }
        // default values
        assertThat(afonLoggingLevel()).isEqualTo("INFO");
        assertThat(afonLoggingConsoleLevel()).isEqualTo("OFF");
    }

    @Test
    public void canOverridePropertyWithEnv() {
        assertThat(envOrProperty("env", "property")).isEqualTo("env");
        assertThat(envOrProperty(null, "property")).isEqualTo("property");
    }

}