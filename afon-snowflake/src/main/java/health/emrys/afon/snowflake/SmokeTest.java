package health.emrys.afon.snowflake;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Properties;
import java.security.PrivateKey;
import net.snowflake.client.jdbc.SnowflakeUtil;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        String url = //"jdbc:snowflake://TVRYLEA-KD37384.snowflakecomputing.com";
                "jdbc:snowflake://TVRYLEA-KD37384.snowflakecomputing.com/"
        ;

        String privateKeyPath = "/home/henryp/Temp/private_key.p8";
        String keyFileContent = new String(Files.readAllBytes(Paths.get(privateKeyPath))); // Files.readAllLines(Paths.get(new URI("file://" + privateKeyPath)));
        keyFileContent = keyFileContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""); // Remove all whitespace

        // 3. Decode the Base64 content
        byte[] decodedKey = Base64.getDecoder().decode(keyFileContent);

        // 4. Create the KeySpec and generate the PrivateKey object
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKey);
        KeyFactory          kf      = KeyFactory.getInstance("RSA");

        PrivateKey privateKey = kf.generatePrivate(keySpec);

        Properties props = new Properties();
        props.put("user", "ETL_SERVICE_USER");
        props.put("db", "SNOWFLAKE_LEARNING_DB");
        props.put("schema", "PUBLIC");
        props.put("warehouse", "COMPUTE_WH");
        props.put("role", "ACCOUNTADMIN");
        props.put("privateKey", privateKey);

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery("SELECT current_version()");
            while (rs.next()) {
                System.out.println("Snowflake version: " + rs.getString(1));
            }
        }

        System.out.println("done");
    }
}
