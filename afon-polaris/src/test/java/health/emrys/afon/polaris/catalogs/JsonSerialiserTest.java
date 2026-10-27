package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JsonSerialiserTest {

    private JsonSerialiser jsonSerialiser = new JsonSerialiser();

    @Test
    public void testPrincipalDeserialization() throws IOException {
        String name = "myprincipalName";
        String clientId = "a_client_id";
        String clientSecret = "a_client_secret";
        var json = String.format(""" 
                   {"principal":{"name":"%s","clientId":"%s","properties":{},"createTimestamp":1700000000000,"lastUpdateTimestamp":1700000000000,"entityVersion":1},"credentials":{"clientId":"%s","clientSecret":"%s"}}
                   """, name, clientId, clientId, clientSecret);
        var result = jsonSerialiser.fromJson(
            new ByteArrayInputStream(json.getBytes()),
            PrincipalWithCredentials.class);
        assertNotNull(result);
        assertEquals(name, result.getPrincipal().getName());
        assertEquals(clientId, result.getCredentials().getClientId());
        assertEquals(clientSecret, result.getCredentials().getClientSecret());
    }

}