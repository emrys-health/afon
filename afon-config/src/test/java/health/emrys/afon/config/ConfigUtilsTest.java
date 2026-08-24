package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.ConfigUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigUtilsTest {

    @Test
    public void canRetrieveAfonHome() {
        System.setProperty(AFON_HOME_SYSTEM_PROPERTY, "/afon/home");
        try {
            assertThat(afonHome()).isEqualTo("/afon/home");
        } finally {
            System.clearProperty(AFON_HOME_SYSTEM_PROPERTY);
        }
        // default value
        assertThat(afonHome()).isEqualTo(System.getenv("HOME")+"/.afon");
    }

    @Test
    public void canRetrieveAfonCdktf() {
        System.setProperty(AFON_CDKTF_SYSTEM_PROPERTY, "/afon/cdktf");
        try {
            assertThat(afonCkdtf()).isEqualTo("/afon/cdktf");
        } finally {
            System.clearProperty(AFON_CDKTF_SYSTEM_PROPERTY);
        }
        // default value
        assertThat(afonCkdtf()).isEqualTo(afonHome()+"/cdktf");
    }

    @Test
    public void canRetrieveCloudProperty() {
        System.setProperty(CLOUD_SYSTEM_PROPERTY, "aws");
        try {
            assertThat(cloudPropertySet()).isTrue();
            assertThat(cloudPropertyValue()).isEqualTo("aws");
        } finally {
            System.clearProperty(CLOUD_SYSTEM_PROPERTY);
        }
        assertThat(cloudPropertySet()).isFalse();
        assertThat(cloudPropertyValue()).isNull();
    }

    @Test
    public void canRetrieveLoggingLevels() {
        System.setProperty(AFON_LOGGING_SYSTEM_PROPERTY, "WARN");
        System.setProperty(AFON_LOGGING_CONSOLE_SYSTEM_PROPERTY, "ERROR");
        try {
            assertThat(afonLoggingLevel()).isEqualTo("WARN");
            assertThat(afonLoggingConsoleLevel()).isEqualTo("ERROR");
        } finally {
            System.clearProperty(AFON_LOGGING_SYSTEM_PROPERTY);
            System.clearProperty(AFON_LOGGING_CONSOLE_SYSTEM_PROPERTY);
        }
        // default values
        assertThat(afonLoggingLevel()).isEqualTo("INFO");
        assertThat(afonLoggingConsoleLevel()).isEqualTo("OFF");
    }


}