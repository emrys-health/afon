package health.emrys;

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

        // 2. Set up connection properties (User is required by Trino)
        Properties properties = new Properties();
        properties.setProperty("user", "analytics_user");
        // If your cluster requires SSL or Password authentication:
        // properties.setProperty("password", "your_password");
        // properties.setProperty("SSL", "true");

        // 3. Define the SQL query
        String sqlQuery = "SELECT customerkey, name, nationkey FROM customer LIMIT 10";

        System.out.println("Connecting to Trino...");

        // 4. Execute the query using try-with-resources
        try (Connection connection = DriverManager.getConnection(url, properties);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sqlQuery)) {

            System.out.println("Connection successful! Executing query...\n");

            // Print headers
            System.out.printf("%-15s | %-25s | %-10s%n", "Customer Key", "Name", "Nation Key");
            System.out.println("------------------------------------------------------------");

            // 5. Iterate through the results
            while (resultSet.next()) {
                long customerKey = resultSet.getLong("customerkey");
                String name = resultSet.getString("name");
                int nationKey = resultSet.getInt("nationkey");

                System.out.printf("%-15d | %-25s | %-10d%n", customerKey, name, nationKey);
            }

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}