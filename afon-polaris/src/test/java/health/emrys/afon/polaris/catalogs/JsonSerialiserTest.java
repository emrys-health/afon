package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.FilePropertyProvider;
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

    @Test
    public void testOutput() throws Exception {
        CloudConfigForPolaris config = new AwsCloudConfigForPolaris(new FilePropertyProvider().getProperties());
        String catalogConfig = config.catalogConfig();
        String expectedJson = """
        {
          "catalog" : {
            "type" : "INTERNAL",
            "name" : "aws",
            "properties" : {
              "default-base-location" : "s3://emrys-afon-bucket",
              "EMRYS_METADATA" : "Emrys catalog for aws"
            },
            "createTimestamp" : 1787071750546,
            "lastUpdateTimestamp" : 1787071750546,
            "storageConfigInfo" : {
              "roleArn" : "arn:aws:iam::123456789012:role/S3ReadWriteRoleSA",
              "allowedKmsKeys" : [ ],
              "region" : "eu-west-2",
              "pathStyleAccess" : false,
              "storageType" : "S3",
              "allowedLocations" : [ "s3://emrys-afon-bucket" ]
            }
          }
        }""";
        //System.out.println(catalogConfig);
        //remove timestamp before comparing
        //assertThat(catalogConfig).isEqualTo(expectedJson);
    }
}