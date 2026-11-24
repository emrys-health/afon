package health.emrys.afon.spark.connect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The token goes to Spark Connect as a parameter of the connection string, which the client sends as a header with every call. */
class SparkConnectClientTest {

    @Test
    void theTokenIsAddedAsTheParameterOfItsHeader() {
        assertThat(SparkConnectClient.withToken("sc://localhost:15002", "t")).isEqualTo("sc://localhost:15002/;x-afon-token=t");
        assertThat(SparkConnectClient.withToken("sc://localhost:15002/", "t")).isEqualTo("sc://localhost:15002/;x-afon-token=t");
    }

    @Test
    void theOtherParametersAreKept() {
        assertThat(SparkConnectClient.withToken("sc://localhost:15002/;user_id=me", "t"))
            .isEqualTo("sc://localhost:15002/;user_id=me;x-afon-token=t");
    }

    @Test
    void thereMustBeAToken() {
        assertThatThrownBy(() -> SparkConnectClient.withToken("sc://localhost:15002", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SparkConnectClient.withToken("sc://localhost:15002", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
