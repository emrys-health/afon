package health.emrys.afon.trino;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class SmokeTest {

    public static void main(String[] args) {
        // 1. Define your Trino connection URL
        // Format: jdbc:trino://<host>:<port>/<catalog>/<schema>
        String url = "jdbc:trino://localhost:8080/local/aws_trino";
        try {
            new SmokeTest(url).readTable("doesnotexist");
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    private final String url;

    public SmokeTest(String url) {
        this.url = url;
    }

    public String readTable(String tableName) throws SQLException {
        // 2. Set up connection properties (User is required by Trino)
        Properties properties = new Properties();
        properties.setProperty("user", "analytics_user");
        // If your cluster requires SSL or Password authentication:
        // properties.setProperty("password", "your_password");
        // properties.setProperty("SSL", "true");

        // 3. Define the SQL query
        String sqlQuery = String.format("SELECT COUNT(*) FROM %s", tableName);

        System.out.println("Connecting to Trino...");

        // 4. Execute the query using try-with-resources
        try (Connection connection = DriverManager.getConnection(url, properties);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sqlQuery)) {

            // 5. Iterate through the results
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return "empty, like my life";
    }
}