package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JsonMakerTest {

    private JsonMaker jsonMaker = new JsonMaker();

    @Test
    public void testPrincipleDeserialization() throws IOException {
        String name = "myprincipleName";
        var json = String.format(""" 
                   {"principal":{"name":"%s","clientId":"2bd68e998ec145a1","properties":{},"createTimestamp":1780656452136,"lastUpdateTimestamp":1780656452136,"entityVersion":1},"credentials":{"clientId":"2bd68e998ec145a1","clientSecret":"***REMOVED***"}}
                   """, name);
        var result = jsonMaker.fromJson(
            new ByteArrayInputStream(json.getBytes()),
            PrincipalWithCredentials.class);
        assertNotNull(result);
        assertEquals(name, result.getPrincipal().getName());
    }

}