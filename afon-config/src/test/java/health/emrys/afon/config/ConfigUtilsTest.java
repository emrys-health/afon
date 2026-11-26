package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.ConfigUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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


    @Test
    public void logFileDefaultsToAfonLog() {
        if (System.getenv(AFON_LOGGING_FILE_ENV) == null) {
            System.clearProperty(AFON_LOGGING_FILE_PROPERTY);
            assertThat(loggingFile()).isEqualTo("afon.log");
        }
    }

    @Test
    public void logFileIsSetByProperty() {
        if (System.getenv(AFON_LOGGING_FILE_ENV) == null) {
            System.setProperty(AFON_LOGGING_FILE_PROPERTY, "aws-full.log");
            try {
                assertThat(loggingFile()).isEqualTo("aws-full.log");
            } finally {
                System.clearProperty(AFON_LOGGING_FILE_PROPERTY);
            }
        }
    }

    @Test
    public void logFileWithDirectoryIsRefused() {
        for (String name : new String[]{"dir/a.log", "../a.log", "/tmp/a.log", "a\\b.log", ".", ".."}) {
            assertThatThrownBy(() -> requireLogFileName(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requireLogFileName("gcp-full.log")).isEqualTo("gcp-full.log");
    }

    @Test
    public void kubeConfigIsPerStackAndCurrentIsALink() {
        assertThat(kubeConfigPath(StackId.parse("gcp-full"))).isEqualTo(afonCkdtf() + "/kube/gcp-full.config");
        assertThat(currentKubeConfigPath()).isEqualTo(afonCkdtf() + "/kube/current.config");
    }

    @Test
    public void onlyASingleKubeConfigIsSupported() {
        assertThat(requireSingleKubeConfig("/cdktf/kube/aws-full.config")).isEqualTo("/cdktf/kube/aws-full.config");
        String list = "/a.config" + java.io.File.pathSeparator + "/b.config";
        assertThatThrownBy(() -> requireSingleKubeConfig(list))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only a single kubeconfig is supported");
    }

    private static void assumeNothingNamesTheCluster() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("KUBECONFIG") == null && System.getenv(AFON_STACK_ENV) == null && System.getenv(AFON_CDKTF_ENV) == null,
                                                      "the environment names a cluster or a cdktf dir");
    }

    @Test
    public void theOpsToolsActOnTheStackDeployedLastWhenNoneIsGiven(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cdktf) throws Exception {
        assumeNothingNamesTheCluster();
        java.nio.file.Path kube = java.nio.file.Files.createDirectories(cdktf.resolve("kube"));
        java.nio.file.Path older = java.nio.file.Files.writeString(kube.resolve("azure-full.config"), "a");
        java.nio.file.Path deployed = java.nio.file.Files.writeString(kube.resolve("aws-full.config"), "b");
        java.nio.file.Files.createSymbolicLink(kube.resolve("current.config"), deployed);
        System.setProperty(AFON_CDKTF_PROPERTY, cdktf.toString());
        try {
            assertThat(opsKubeConfigPath()).isEqualTo(deployed.toRealPath().toString()).isNotEqualTo(older.toString());
            // a stack that is given wins over the one deployed last
            System.setProperty(AFON_STACK_PROPERTY, "azure-full");
            assertThat(opsKubeConfigPath()).isEqualTo(cdktf + "/kube/azure-full.config");
        } finally {
            System.clearProperty(AFON_STACK_PROPERTY);
            System.clearProperty(AFON_CDKTF_PROPERTY);
        }
    }

    @Test
    public void theOpsToolsAreToldWhatToSetWhenNoStackWasDeployedAndNoneIsGiven(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cdktf) {
        assumeNothingNamesTheCluster();
        System.setProperty(AFON_CDKTF_PROPERTY, cdktf.toString());
        try {
            assertThatThrownBy(ConfigUtils::opsKubeConfigPath).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No cluster to act on").hasMessageContaining("KUBECONFIG").hasMessageContaining("--stack");
        } finally {
            System.clearProperty(AFON_CDKTF_PROPERTY);
        }
    }
}
