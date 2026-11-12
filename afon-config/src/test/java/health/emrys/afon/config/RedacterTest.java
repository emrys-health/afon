package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class RedacterTest {

    @Test
    void redactsTheValueOfASecretByTheNameOfItsKey() {
        Properties secrets = new Properties();
        secrets.setProperty("AZURE_APP_SECRET", "s3cr3t-value");

        assertThat(Redacter.redactSecrets("password s3cr3t-value here", "test", secrets)).isEqualTo("password <azure_app_secret> here");
    }

    @Test
    void doesNotRedactBlankValuesOrCommonWords() {
        Properties secrets = new Properties();
        secrets.setProperty("BLANK", "");
        secrets.setProperty("NAME", "afon");

        assertThat(Redacter.redactSecrets("afon", "test", secrets)).isEqualTo("afon");
    }

    @Test
    void doesNotRedactTheBooleansOfFlagsInTheSecretFiles() {
        // eg AZURE_WORKLOAD_IDENTITY is a flag, which the template keeps beside the secrets of the app: its value is not one, and as a secret it would replace
        // every true or false of a log or a doc
        Properties secrets = new Properties();
        secrets.setProperty("AZURE_WORKLOAD_IDENTITY", "true");
        secrets.setProperty("OTHER_FLAG", "false");

        assertThat(Redacter.redactSecrets("azure-create [true|false] <stack>", "test", secrets)).isEqualTo("azure-create [true|false] <stack>");
    }
}
