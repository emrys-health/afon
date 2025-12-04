package health.emrys.afon.snowflake;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        String url = //"jdbc:snowflake://TVRYLEA-KD37384.snowflakecomputing.com";
            "jdbc:snowflake://TVRYLEA-KD37384.snowflakecomputing.com/"; //?query_tag='folder=folder1 folder2&'";
        Properties props = new Properties();
        props.put("user", "PHILLHENRY");
        props.put("password", "MSSux8rse!");
        props.put("db", "SNOWFLAKE_LEARNING_DB");
        props.put("schema", "PUBLIC");
        props.put("warehouse", "COMPUTE_WH");
        props.put("role", "ACCOUNTADMIN");

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery("SELECT current_version()");
            while (rs.next()) {
                System.out.println("Snowflake version: " + rs.getString(1));
            }
        }
    }
}
