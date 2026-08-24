package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;

public class JsonSerialiser {

    public String toJson(Object object) throws JsonProcessingException {
        return getObjectMapper().writeValueAsString(object);
    }

    private static ObjectMapper getObjectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public <T> T fromJson(InputStream is, Class<T> clazz) throws IOException {
        return getObjectMapper().readValue(is, clazz);
    }
}
