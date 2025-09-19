package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import health.emrys.afon.config.AfonPropertiesLoader;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.PROJECT_PROPERTIES_FILE;

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
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String json = mapper.writeValueAsString(object);
        return json;
    }
}
