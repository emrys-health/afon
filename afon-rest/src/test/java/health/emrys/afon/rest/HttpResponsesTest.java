package health.emrys.afon.rest;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpResponsesTest {

    @Test
    void parsesTheAccessToken() throws Exception {
        assertThat(HttpResponses.parseAccessToken(response(200, "{\"access_token\":\"a_token\"}"))).isEqualTo("a_token");
    }

    @Test
    void serverErrorsMayBeRetried() {
        assertThatThrownBy(() -> HttpResponses.parseAccessToken(response(503, "unavailable")))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("503");
        assertThatThrownBy(() -> HttpResponses.parseAccessToken(null))
            .isInstanceOf(IOException.class);
    }

    @Test
    void otherFailuresAreNotRetried() {
        assertThatThrownBy(() -> HttpResponses.parseAccessToken(response(401, "{\"error\":\"invalid_client\"}")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("401")
            .hasMessageContaining("invalid_client");
    }

    @Test
    void aResponseWithoutAnAccessTokenIsAFailure() {
        assertThatThrownBy(() -> HttpResponses.parseAccessToken(response(200, "{\"access_token\":\" \"}")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HttpResponses.parseAccessToken(response(200, "{}")))
            .isInstanceOf(IllegalStateException.class);
    }

    private static ClassicHttpResponse response(int code, String body) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(code);
        response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return response;
    }
}
