package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import health.emrys.afon.config.AfonPropertiesLoader;

import java.io.IOException;
import java.io.InputStream;

public class JsonMaker {
    /**
     * For testing the config JSON only.
     */
    public static void main(String[] args) {
        var app = new JsonMaker();
        try {
            app.smokeTestOutput();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void smokeTestOutput() throws Exception {
        AfonPropertiesLoader  loader = new AfonPropertiesLoader();
        CloudConfigForPolaris config = new AwsCloudConfigForPolaris(loader.getProperties());
        System.out.println(config.catalogConfig());
    }

    public String toJson(Object object) throws JsonProcessingException {
        ObjectMapper mapper = getObjectMapper();
        String json = mapper.writeValueAsString(object);
        return json;
    }

    private static ObjectMapper getObjectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public <T> T fromJson(InputStream is, Class<T> clazz) throws IOException {
        ObjectMapper mapper = getObjectMapper();
        return mapper.readValue(is, clazz);
    }
}
