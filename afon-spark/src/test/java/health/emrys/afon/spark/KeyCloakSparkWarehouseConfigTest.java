package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static org.assertj.core.api.Assertions.assertThat;

class KeyCloakSparkWarehouseConfigTest {

    private static final String CLIENT_ID     = "a_client";
    private static final String CLIENT_SECRET = "a_secret";

    private final SparkWarehouseConfig config = new KeyCloakSparkWarehouseConfig(properties());

    private static AfonPropertyProvider properties() {
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        properties.setValue(POLARIS_ENDPOINT, "polaris.example.com");
        properties.setValue(OIDC_DOMAIN_NAME, "keycloak.example.com");
        properties.setValue(USER_POLARIS_ID, CLIENT_ID);
        properties.setValue(USER_POLARIS_SECRET, CLIENT_SECRET);
        return properties;
    }

    @Test
    void tokenConfigsAuthenticateWithTokenOnly() {
        Map<String, String> configs = config.sparkConfigsWithToken("spark_aws", "aws", "a_token");

        assertThat(configs)
            .containsEntry("spark.sql.catalog.spark_aws.token", "a_token")
            .containsEntry("spark.sql.catalog.spark_aws.warehouse", "aws")
            .containsEntry("spark.sql.catalog.spark_aws.scope", "openid")
            .containsEntry("spark.sql.catalog.spark_aws.oauth2-server-uri",
                           "https://keycloak.example.com/realms/polaris/protocol/openid-connect/token")
            .doesNotContainKey("spark.sql.catalog.spark_aws.credential");
        assertThat(configs.values()).noneMatch(value -> value.contains(CLIENT_SECRET));
    }

    @Test
    void credentialConfigsAuthenticateWithClientCredentials() {
        Map<String, String> configs = config.sparkConfigs("spark_aws", "aws");

        assertThat(configs)
            .containsEntry("spark.sql.catalog.spark_aws.credential", CLIENT_ID + ":" + CLIENT_SECRET)
            .containsEntry("spark.sql.catalog.spark_aws.scope", "openid")
            .doesNotContainKey("spark.sql.catalog.spark_aws.token");
    }
}
