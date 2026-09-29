package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.ConfigUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigUtilsTest {

    @Test
    public void canRetrieveAfonHome() {
        System.setProperty(AFON_HOME_PROPERTY, "/afon/home");
        String env = System.getenv(AFON_HOME_ENV);
        try {
            if ( env != null ) {
                assertThat(afonHome()).isEqualTo(env);
            } else {
                assertThat(afonHome()).isEqualTo("/afon/home");
            }
        } finally {
            System.clearProperty(AFON_HOME_PROPERTY);
        }
        if ( env == null ) {
            // default value
            assertThat(afonHome()).isEqualTo(System.getenv("HOME")+"/.afon");
        }
    }

    @Test
    public void canRetrieveAfonCdktf() {
        System.setProperty(AFON_CDKTF_PROPERTY, "/afon/cdktf");
        String env = System.getenv(AFON_CDKTF_ENV);
        try {
            if ( env != null ) {
                assertThat(afonCkdtf()).isEqualTo(env);
            } else {
                assertThat(afonCkdtf()).isEqualTo("/afon/cdktf");
            }
        } finally {
            System.clearProperty(AFON_CDKTF_PROPERTY);
        }
        if ( env == null ) {
            // default value
            assertThat(afonCkdtf()).isEqualTo(afonHome()+"/cdktf");
        }
    }

    @Test
    public void canRetrieveAfonCloud() {
        System.setProperty(AFON_CLOUD_PROPERTY, "cloud");
        String env = System.getenv(AFON_CLOUD_ENV);
        try {
            if ( env != null ){
                assertThat(afonCloud()).isEqualTo(env);
            } else {
                assertThat(afonCloud()).isEqualTo("cloud");
            }
        } finally {
            System.clearProperty(AFON_CLOUD_PROPERTY);
        }
        if ( env == null ) {
            // default value
            assertThat(afonCloud()).isEqualTo("");
        }
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
    public void canRetrieveLoggingLevel() {
        System.setProperty(AFON_LOGGING_PROPERTY, "WARN");
        String env = System.getenv(AFON_LOGGING_ENV);
        try {
            if ( env != null ) {
                assertThat(afonLoggingLevel()).isEqualTo(env);
            } else  {
                assertThat(afonLoggingLevel()).isEqualTo("WARN");
            }
        } finally {
            System.clearProperty(AFON_LOGGING_PROPERTY);
        }
        if ( env == null ) {
            // default values
            assertThat(afonLoggingLevel()).isEqualTo("INFO");
        }
    }

    @Test
    public void canRetrieveLoggingConsoleLevel() {
        System.setProperty(AFON_LOGGING_CONSOLE_PROPERTY, "ERROR");
        String env = System.getenv(AFON_LOGGING_CONSOLE_ENV);
        try {
            if ( env != null ) {
                assertThat(afonLoggingConsoleLevel()).isEqualTo(env);
            } else  {
                assertThat(afonLoggingConsoleLevel()).isEqualTo("ERROR");
            }
        } finally {
            System.clearProperty(AFON_LOGGING_CONSOLE_PROPERTY);
        }
        if ( env == null ) {
            // default values
            assertThat(afonLoggingConsoleLevel()).isEqualTo("OFF");
        }
    }

    @Test
    public void canRetrieveStackPathsPerStackId() {
        StackId stackId = new StackId(AfonCloud.AWS, "keycloak");
        assertThat(stacksDir()).isEqualTo(afonCkdtf() + "/stacks");
        assertThat(stackDir(stackId)).isEqualTo(afonCkdtf() + "/stacks/aws-keycloak");
        assertThat(tfStatePath(stackId)).isEqualTo(afonCkdtf() + "/terraform.aws-keycloak.tfstate");
        assertThat(kubeConfigPath(stackId)).isEqualTo(afonCkdtf() + "/kube/aws-keycloak.config");
        assertThat(legacyStackDir(AfonCloud.AWS)).isEqualTo(afonCkdtf() + "/stacks/aws");
        assertThat(legacyTfStatePath(AfonCloud.AWS)).isEqualTo(afonCkdtf() + "/terraform.aws.tfstate");
    }

    @Test
    public void canOverridePropertyWithEnv() {
        assertThat(envOrProperty("env", "property")).isEqualTo("env");
        assertThat(envOrProperty(null, "property")).isEqualTo("property");
    }

}