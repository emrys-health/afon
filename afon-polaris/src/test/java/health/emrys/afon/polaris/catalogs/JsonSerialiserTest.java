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
        var json = String.format(""" 
                   {"principal":{"name":"%s","clientId":"2bd68e998ec145a1","properties":{},"createTimestamp":1780656452136,"lastUpdateTimestamp":1780656452136,"entityVersion":1},"credentials":{"clientId":"2bd68e998ec145a1","clientSecret":"***REMOVED***"}}
                   """, name);
        var result = jsonSerialiser.fromJson(
            new ByteArrayInputStream(json.getBytes()),
            PrincipalWithCredentials.class);
        assertNotNull(result);
        assertEquals(name, result.getPrincipal().getName());
    }

}