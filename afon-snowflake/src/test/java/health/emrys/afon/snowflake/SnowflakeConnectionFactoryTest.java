package health.emrys.afon.snowflake;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonProperty.SNOWFLAKE_JDBC_URL;
import static health.emrys.afon.config.AfonProperty.SNOWFLAKE_PRIVATE_KEY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeConnectionFactoryTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void failsClearlyIfTheUrlIsNotSet() {
        properties.setValue(SNOWFLAKE_JDBC_URL, "");
        properties.setValue(SNOWFLAKE_PRIVATE_KEY, "key");
        assertThatThrownBy(() -> new SnowflakeConnectionFactory(properties))
            .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("SNOWFLAKE_JDBC_URL is not set");
    }

    @Test
    void failsClearlyIfThePrivateKeyIsNotSet() {
        properties.setValue(SNOWFLAKE_JDBC_URL, "jdbc:snowflake://account.snowflakecomputing.com");
        properties.setValue(SNOWFLAKE_PRIVATE_KEY, " ");
        assertThatThrownBy(() -> new SnowflakeConnectionFactory(properties))
            .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("SNOWFLAKE_PRIVATE_KEY is not set");
    }
}
